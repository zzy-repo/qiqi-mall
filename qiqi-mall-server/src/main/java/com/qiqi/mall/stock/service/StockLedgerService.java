package com.qiqi.mall.stock.service;

import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.StockProperties;
import com.qiqi.mall.stock.StockStore;
import com.qiqi.mall.stock.mapper.StockLedgerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * SKU 库存台账服务：**所有扣/补库存的唯一通道**（{@code SkuServiceImpl} 全量转到这里）。
 * 落点是 {@code sku_bucket}（库存权威），{@code sku.stock} 只是展示缓存。
 *
 * <h3>它修的是什么</h3>
 * 缺陷：同一件货可能被回补两次。
 * <pre>
 * 入口 A：审核不通过 → 按整单回补   ┐
 * 入口 B：取消包裹   → 按该包裹回补 ┘ 两边都是裸 UPDATE sku SET stock = stock + N
 * </pre>
 * 先「审核通过又取消包裹」或「审核不通过后又从别的入口退款」，同一件货就被补两次 → 库存虚增。
 *
 * <h3>两道闸门（都必须过）</h3>
 * <ol>
 *   <li><b>幂等键</b> {@code orderId:skuId:reason:refId}（台账表 UNIQUE）：同一个触发重放时，
 *       {@code INSERT ... ON DUPLICATE KEY} 返回 0 → <b>不重复回补</b>，记一条 warn
 *       「已回补过，跳过」。这一道管「同一入口被点两次 / 消息重投」。</li>
 *   <li><b>数量守恒</b>：同一 (订单, 规格) 的累计已回补量 ≤ 该订单该规格的下单总量
 *       （{@code order_item.product_quantity} 之和）。这一道管「<b>不同入口</b>各自回补同一件货」——
 *       幂等键里带 reason，所以换个入口就是一个新键，只有守恒约束拦得住。
 *       超量时把本次回补<b>夹紧</b>到剩余可回补量（不够就跳过），并记 warn。</li>
 * </ol>
 *
 * <h3>记账顺序</h3>
 * <b>先记账、后动库存</b>：账记不上（幂等键已存在 / 守恒不成立）就一步都不动库存。
 * 反过来（先加库存再记账）一旦记账失败就会留下「库存已加、账上没有」的黑洞。
 * 扣减方向若因库存不足失败，会把刚记的那行撤销（补偿），不留「账上扣了、库存没扣」的假账。
 *
 * <h3>记账之后动的是「桶」，不是 sku.stock</h3>
 * 扣减/回补的实际落点是 {@code sku_bucket}（见 {@link SkuBucketService}）：同一份库存物理拆成 N 行，
 * 并发不再挤在 {@code sku} 那一把行锁上。{@code sku.stock} 已降级为展示缓存，
 * <b>本类的两条路径都不写它</b>（写了就等于把热行锁请回来）；它由
 * {@code StockCacheRefresher} 按 {@code SUM(桶)} 周期回写。
 *
 * <p>台账新增的 {@code bucket_no} 只用于「回补回到原桶」，<b>不参与守恒计算</b>：
 * 守恒仍然只看 {@code delta} 的和。
 *
 * <p>事务：本类不自带 {@code @Transactional} —— 它必须与调用方的业务事务同一个（台账行要和
 * 库存、订单状态一起提交/回滚）。
 */
@Service
public class StockLedgerService {

    private static final Logger log = LoggerFactory.getLogger(StockLedgerService.class);

    private final StockLedgerMapper ledgerMapper;
    /**
     * 库存落点（启动时二选一：legacy 单行 UPDATE / bucket 分桶）。
     * 本类只负责记账与两道闸门 —— 台账逻辑在两条路径下**完全共用**，
     * 不允许出现「分桶有账、legacy 没账」这种两套账。
     */
    private final StockStore stockStore;
    private final StockProperties properties;

    public StockLedgerService(StockLedgerMapper ledgerMapper, StockStore stockStore, StockProperties properties) {
        this.ledgerMapper = ledgerMapper;
        this.stockStore = stockStore;
        this.properties = properties;
    }

    /** 台账插入写法：{@code naive}（INSERT...SELECT NOT EXISTS，baseline）/ {@code ignore}（INSERT IGNORE） */
    public String ledgerMode() {
        return properties.isLedgerInsertIgnore() ? "ignore" : "naive";
    }

    /** 幂等键前缀桶数（1 = 不加前缀，与历史数据逐字节一致）；启动日志与排障用 */
    public int idemKeyShards() {
        return properties.getIdemKeyShards();
    }

    /**
     * 扣减库存（下单）。先记一条负数台账，再原子扣**某个桶**（{@code sku_bucket}），
     * 最后把命中的桶号回填到台账（{@code bucket_no}，供回补定位）。
     *
     * @return true = 扣减成功（或同一笔下单的重放，视为已扣）；false = 库存不足，库存与台账都未变
     */
    public boolean deduct(Long skuId, int quantity) {
        if (quantity <= 0) return true;
        StockLedgerContext.Attribution attribution = requireContext("扣减");
        String idemKey = attribution.idemKey(skuId, properties.getIdemKeyShards());

        boolean merged = false;
        if (StockLedgerContext.alreadyTouchedInThisRun(idemKey)) {
            // 同一次触发里的第二条明细（同一订单两行买同一规格）：件数合并进同一行台账
            merged = ledgerMapper.addDelta(idemKey, -quantity) > 0;
        }
        if (!merged) {
            int inserted = insertIfAbsent(attribution, skuId, -quantity, idemKey);
            if (inserted == 0) {
                // 幂等键已存在 = 同一笔下单被重放。下单本身有幂等令牌，正常不会走到这里；
                // 走到这里说明令牌 + 台账里有一层漏了，宁可不重复扣，也要把话说明白。
                log.warn("下单扣减命中已存在的台账幂等键 {}（订单 {} 规格 {}），本次不重复扣减",
                        idemKey, attribution.orderId(), skuId);
                return true;
            }
            StockLedgerContext.markTouched(idemKey);
        }

        // 权威裁决：legacy = sku 那一行的原子 UPDATE；bucket = 随机起点轮询 N 个桶
        StockStore.StockWrite write = stockStore.deduct(skuId, quantity);
        if (!write.ok()) {
            // 库存不足：账不能记在没发生的扣减上 —— 撤掉刚记的这行（调用方随后抛异常回滚整笔下单）
            if (merged) {
                ledgerMapper.addDelta(idemKey, quantity);
            } else {
                ledgerMapper.deleteByIdemKey(idemKey);
            }
            log.warn("库存不足：订单 {} 规格 {} 需要 {} 件，扣减未发生，台账已撤销（key={}）",
                    attribution.orderId(), skuId, quantity, idemKey);
            return false;
        }
        // 桶号回填进台账：回补时据此回到原桶（不参与守恒，只做定位）。legacy 模式没有桶，跳过
        if (write.bucketNo() != null) {
            ledgerMapper.attachBucketNo(idemKey, write.bucketNo());
        }
        log.info("库存扣减已记账：订单 {} 规格 {} delta={} reason={} refType={} refId={} bucket={} key={}",
                attribution.orderId(), skuId, -quantity, attribution.reason(),
                attribution.refType(), attribution.refId(), write.bucketNo(), idemKey);
        return true;
    }

    /**
     * 回补库存（审核不通过 / 取消包裹 / 退货退款）。先过两道闸门，再记正数台账，
     * 最后把货加回**台账里记的那个桶**（没有桶号的历史账按 sku 任选一个桶）。
     *
     * @return 实际回补件数；**0 = 已回补过（或已无剩余可回补量），本次跳过、库存未变**
     */
    public int restore(Long skuId, int quantity) {
        if (quantity <= 0) return 0;
        StockLedgerContext.Attribution attribution = requireContext("回补");
        String idemKey = attribution.idemKey(skuId, properties.getIdemKeyShards());

        // 闸门 ②：数量守恒（不同入口回补同一件货时只有这一道拦得住）
        int allowed = clampToOrderedQty(attribution.orderId(), skuId, quantity);
        if (allowed <= 0) {
            log.warn("跳过回补（数量守恒）：订单 {} 的规格 {} 下单 {} 件、已回补 {} 件，本次要求再补 {} 件"
                            + " reason={} refType={} refId={} —— 再补就超过下单量了，按「同一件货不补两次」跳过",
                    attribution.orderId(), skuId,
                    ledgerMapper.sumOrderedQty(attribution.orderId(), skuId),
                    ledgerMapper.sumRestoredQty(attribution.orderId(), skuId),
                    quantity, attribution.reason(), attribution.refType(), attribution.refId());
            return 0;
        }

        // 闸门 ①：幂等键（同一触发重放）
        boolean merged = false;
        if (StockLedgerContext.alreadyTouchedInThisRun(idemKey)) {
            merged = ledgerMapper.addDelta(idemKey, allowed) > 0;
        }
        if (!merged) {
            int inserted = insertIfAbsent(attribution, skuId, allowed, idemKey);
            if (inserted == 0) {
                log.warn("已回补过，跳过：台账幂等键 {} 已存在（订单 {} 规格 {} reason={} refType={} refId={}），"
                                + "本次不再回补 {} 件，库存保持不变",
                        idemKey, attribution.orderId(), skuId, attribution.reason(),
                        attribution.refType(), attribution.refId(), quantity);
                return 0;
            }
            StockLedgerContext.markTouched(idemKey);
        }

        // 回到原桶：扣减时记在台账上的 bucket_no（历史台账没有桶号时，服务层按 sku 任选一个桶）。
        // legacy 模式没有桶的概念，连这次查询都不发。
        Integer preferredBucketNo = stockStore.usesBuckets()
                ? ledgerMapper.findDeductBucketNo(attribution.orderId(), skuId)
                : null;
        StockStore.StockWrite write = stockStore.restore(skuId, allowed, preferredBucketNo);
        if (!write.ok()) {
            // 规格不存在 / 无法建桶：账已记、库存没变 → 抛异常让调用方事务整笔回滚（fail-closed）
            throw new IllegalStateException("回补库存失败：SKU " + skuId
                    + " 没有可用的库存桶（规格可能已被删除）（台账行将随事务回滚）");
        }
        // 回补行也记下桶号：这一件货「回到哪个桶」同样可对账（查询只认 delta<0 的行，不会混淆）
        if (write.bucketNo() != null) {
            ledgerMapper.attachBucketNo(idemKey, write.bucketNo());
        }
        if (allowed < quantity) {
            log.warn("回补件数已夹紧到剩余可回补量：订单 {} 规格 {} 要求 {} 件，实际只补 {} 件"
                            + "（其余此前已回补过） reason={}",
                    attribution.orderId(), skuId, quantity, allowed, attribution.reason());
        }
        log.info("库存回补已记账：订单 {} 规格 {} delta={} reason={} refType={} refId={} bucket={}（台账桶号 {}）key={}",
                attribution.orderId(), skuId, allowed, attribution.reason(),
                attribution.refType(), attribution.refId(), write.bucketNo(), preferredBucketNo, idemKey);
        return allowed;
    }

    /**
     * 剩余可回补量 = 下单总量 − 已回补总量，再与本次要求量取小，且不小于 0。
     *
     * <p>基准取 {@code order_item.product_quantity} 而不是「台账里的 ORDER_CREATE 扣减行」：
     * 后台建单 / 历史订单没有扣减台账，但「这单买了多少件」一定在库里 ——
     * 用它当基准，守恒对**所有**订单都成立，不依赖下单有没有记过账。
     */
    private int clampToOrderedQty(Long orderId, Long skuId, int quantity) {
        int ordered = ledgerMapper.sumOrderedQty(orderId, skuId);
        int restored = ledgerMapper.sumRestoredQty(orderId, skuId);
        int headroom = ordered - restored;
        return Math.max(0, Math.min(quantity, headroom));
    }

    /**
     * 记一行台账：1 = 新记（幂等键首次出现）；0 = 幂等键已存在（重放）或并发撞键被 UNIQUE 拦下。
     *
     * <h3>两种写法（开关 {@code qiqi.stock.ledger-insert-ignore}）</h3>
     * <ul>
     *   <li><b>naive（baseline，{@code ledger-insert-ignore=false}）</b>：
     *       {@code INSERT ... SELECT ... WHERE NOT EXISTS}。
     *       ⚠️ 压测实证它就是死锁源头（6 档累计 45,790 次，失败=「系统繁忙，请重试」）：
     *       {@code INSERT ... SELECT} 对源表做**加锁读**，在 REPEATABLE READ 下对 uk_idem 取共享
     *       next-key/gap 锁，并发插入的插入意向锁与之成环。本机探针已复现（2000 条里 3 次 1213）。</li>
     *   <li><b>ignore（{@code ledger-insert-ignore=true}）</b>：{@code INSERT IGNORE ... VALUES} ——
     *       只取插入意向锁、不读源表、不产生共享 gap 锁；冲突时 0 行 = 已存在 = 重放，
     *       <b>判据一字不改</b>（仍是「插入 0 行即重放」）。本机探针同负载下 1213 归零。</li>
     * </ul>
     *
     * <h3>为什么 INSERT IGNORE 必须配一条回查</h3>
     * {@code INSERT IGNORE} 会吞掉**所有**错误（字段超长、NOT NULL … 都会静默降级成 0 行/警告）。
     * 若把它一律当成「重放」，就是「账没记上却当记过了」——比死锁更糟。
     * 所以 0 行时立刻按幂等键回查：查得到 = 真的是重复（正常重放）；
     * 查不到 = 被吞掉的是别的错误 → 抛异常（fail-closed，绝不静默放过）。
     */
    private int insertIfAbsent(StockLedgerContext.Attribution attribution, Long skuId, int delta, String idemKey) {
        if (!properties.isLedgerInsertIgnore()) {
            try {
                return ledgerMapper.insertIfAbsent(attribution.orderId(), skuId, delta, attribution.reason(),
                        attribution.refType(), attribution.refId(), idemKey, LocalDateTime.now());
            } catch (DuplicateKeyException e) {
                // 并发下两个请求同时通过 NOT EXISTS：唯一键是最后一道闸，按「已记过账」处理
                log.warn("台账幂等键并发撞键（{}），按已记账处理：{}", idemKey, e.getMessage());
                return 0;
            }
        }

        int rows = ledgerMapper.insertIgnore(attribution.orderId(), skuId, delta, attribution.reason(),
                attribution.refType(), attribution.refId(), null, idemKey);
        if (rows == 0 && ledgerMapper.countByIdemKey(idemKey) == 0) {
            // 0 行但幂等键不存在 ⇒ 不是「重复」，是 INSERT IGNORE 吞掉了别的错误
            throw new IllegalStateException("台账插入被 INSERT IGNORE 吞掉但幂等键不存在（不是重放）："
                    + "key=" + idemKey + " orderId=" + attribution.orderId() + " skuId=" + skuId
                    + " delta=" + delta + " —— 大概率是字段超长/约束冲突，拒绝静默当作「已记账」");
        }
        return rows;
    }

    /** 没有上下文就不许动库存：无台账的库存变更正是这个缺陷的本体 */
    private StockLedgerContext.Attribution requireContext(String action) {
        StockLedgerContext.Attribution attribution = StockLedgerContext.current();
        if (attribution == null || attribution.orderId() == null || attribution.reason() == null) {
            throw new IllegalStateException("库存" + action + "缺少台账上下文（订单/原因未知）："
                    + "所有扣/补库存的入口都必须先用 StockLedgerContext.open(orderId, reason, refType, refId) "
                    + "声明归属，否则无法记账、无法查重 —— 拒绝执行");
        }
        return attribution;
    }
}
