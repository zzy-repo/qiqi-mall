package com.qiqi.mall.stock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 库存分桶配置（{@code qiqi.stock.*}）。
 *
 * <p>为什么桶数要做成配置项而不是常量：桶数是「并发度 vs 管理成本」的旋钮 ——
 * 热点 SKU 越多、并发越高就越值得多拆几行；桶越多，单桶余量越小（见 {@code SkuBucketService}
 * 的跨桶拼单），运维成本（SUM 汇总的行数）也越高。压测时要能不改代码就调。
 *
 * <p><b>必须与 SQL 保持一致</b>：{@code sql/_rollback/migrate-sku-bucket.sql} 的回填把
 * N 写死成 8（SQL 里读不到应用配置）。改 {@code bucket-count} 就要同步改那份回填，
 * 否则已有 SKU 只有 0..7 号桶、而应用会去找 8、9 号桶（多出来的尝试全部落空，不会算错库存，
 * 但白跑几次 UPDATE）。
 */
@Component
@ConfigurationProperties(prefix = "qiqi.stock")
public class StockProperties {

    /**
     * 扣减路径开关：false = 原来的单行 UPDATE（baseline），true = 走 sku_bucket 分桶。
     *
     * <p><b>默认 false</b>：压测要分别归因两个优化，镜像 1/2 必须是「没有分桶变量」的，
     * 否则连接池与台账修复的收益会被分桶混在一起，算不清。
     */
    private boolean bucketEnabled = false;

    /**
     * 台账插入写法开关：false = `INSERT ... SELECT ... WHERE NOT EXISTS`（baseline），
     * true = `INSERT IGNORE ... VALUES`。
     *
     * <p>为什么做成开关：压测实证 baseline 的 45,790 次死锁全部落在这条插入上
     * （`INSERT ... SELECT` 的读侧对 uk_idem 取共享 gap 锁，与并发插入的意向锁成环）。
     * 更关键的是 P2 实验（100% 冷门 SKU、无热点，c16 只快 0.9%）证明**台账才是全局串行点**，
     * 所以「台账修复」和「分桶」是两笔独立收益，必须分两次镜像量（L 阶段 / E 阶段）。
     */
    private boolean ledgerInsertIgnore = false;

    /**
     * 幂等键打散的「前缀桶数」：1 = 不加前缀（与今天逐字节一致，默认）；&gt;1 = 给 idem_key 加
     * {@code LPAD(orderId % N, 位宽, '0') + ":"} 前缀。
     *
     * <h3>为什么需要</h3>
     * {@code uk_idem} 是唯一二级索引，插入时 InnoDB 要在该 key 所在 **gap** 上取共享锁做重复检查，
     * 而并发插入同一 gap 要拿插入意向锁 → 互斥。{@code idem_key} 以单调递增的 orderId 开头，
     * 于是**所有插入都落在 uk_idem 最右端同一个 gap**，变成全局单点排队
     * （压测：等待次数与并发无关地恒定在 ~2.8 万/分钟，等待时长 c8 19ms → c32 125ms 线性增长，TPS 钉死在 ~300）。
     * 加前缀后插入按 {@code orderId % N} 轮转分散到 N 个 gap，互斥面直接除以 N。
     *
     * <h3>为什么 64 而不是更大</h3>
     * ① 按压测镜像当时的双实例连接池上限 30×2=60 估算（`application-*.yml` 现已是 Spring Boot
     *    默认 10×2=20，连接池调参已整体移除），N=64 时峰值下平均每个 gap 不足一个连接，收益已接近饱和；
     * ② {@code idem_key} 是 VARCHAR(96)：最坏情况原键 92 字符（19+1+19+1+32+1+19），
     *    N≤64 时前缀最多 3 字符（"63:"）→ 95，**不用改表**；N=128 前缀 4 字符就正好 96 顶格，
     *    再大必须先 {@code ALTER ... MODIFY idem_key VARCHAR(128)}。
     *
     * <h3>⚠️ 切这个开关的顺序（不可并发）</h3>
     * <b>停流量 → 跑 sql/_rollback/migrate-ledger-idem-shard.sql 回填旧行 → 起新镜像</b>
     * （该脚本是历史脚本、已下线：新库结构由 Flyway 基线 {@code db/migration/V*.sql} 带来，不用跑；
     * 只有「早就在跑、库里还是无前缀旧键」的老数据卷需要手工补跑这一遍回填）。
     * 反了就会出事：旧触发重放时算出的是「带前缀」的新键，而库里那行还是「无前缀」的旧键 →
     * 查不到 → 被当成首次触发 → 重复扣减/重复回补。
     */
    private int idemKeyShards = 1;

    /** 桶数：一份库存物理拆成几行。1 = 退化成原来的单行串行（可用于压测对照） */
    private int bucketCount = 8;

    /** 展示缓存回写周期（毫秒）：扣/补路径只标脏，真正的 sku.stock 回写在这个周期里做 */
    private long cacheRefreshMs = 250;

    /** 每个周期最多回写多少个 SKU（限制单次任务的耗时，避免一次刷新拖成毛刺） */
    private int cacheRefreshBatch = 200;

    /**
     * 桶数钳在 [1, 64]：0 或负数会让扣减逻辑「一次都不试」直接判库存不足；
     * 上界只是防手抖（64 桶 × 每 SKU 8 字节 = 512 字节，再大就纯属给 SUM 找麻烦）。
     */
    public int getBucketCount() {
        return Math.min(64, Math.max(1, bucketCount));
    }

    /**
     * 幂等键前缀桶数（钳在 [1, 1000]；1 = 不加前缀）。
     *
     * <p>上界 1000 不是随手写的：{@code idem_key} 是 VARCHAR(96)，理论最坏情况
     * 19+1+19+1+32+1+19 = 92 字符（reason 列允许 32 字符）。前缀 = 位宽 + 冒号，
     * 所以 93 + 位宽 必须 ≤ 96 ⇒ 位宽 ≤ 3 ⇒ N ≤ 1000。
     * N=1024 时位宽 4 → 97 字符会被**静默截断**，键就对不上了（幂等失效）——所以钳在这里。
     */
    public int getIdemKeyShards() {
        return Math.min(1000, Math.max(1, idemKeyShards));
    }

    public void setIdemKeyShards(int idemKeyShards) {
        this.idemKeyShards = idemKeyShards;
    }

    public boolean isBucketEnabled() {
        return bucketEnabled;
    }

    public void setBucketEnabled(boolean bucketEnabled) {
        this.bucketEnabled = bucketEnabled;
    }

    public boolean isLedgerInsertIgnore() {
        return ledgerInsertIgnore;
    }

    public void setLedgerInsertIgnore(boolean ledgerInsertIgnore) {
        this.ledgerInsertIgnore = ledgerInsertIgnore;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = bucketCount;
    }

    /** 回写周期钳在 [50ms, 60s]：太小等于把热行锁请回来，太大则展示延迟难看 */
    public long getCacheRefreshMs() {
        return Math.min(60_000L, Math.max(50L, cacheRefreshMs));
    }

    public void setCacheRefreshMs(long cacheRefreshMs) {
        this.cacheRefreshMs = cacheRefreshMs;
    }

    public int getCacheRefreshBatch() {
        return Math.min(5_000, Math.max(1, cacheRefreshBatch));
    }

    public void setCacheRefreshBatch(int cacheRefreshBatch) {
        this.cacheRefreshBatch = cacheRefreshBatch;
    }
}
