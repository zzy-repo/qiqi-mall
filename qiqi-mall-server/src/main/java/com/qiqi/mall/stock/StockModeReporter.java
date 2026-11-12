package com.qiqi.mall.stock;

import com.qiqi.mall.stock.service.StockLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时打**一行**模式日志 —— 压测方靠它确认「这个镜像到底跑的是哪条路径」。
 *
 * <p>格式固定为（便于 grep / 比对镜像）：
 * <pre>
 * stock.mode=legacy ledger=naive idem-shards=1
 * stock.mode=bucket(N=8) ledger=ignore idem-shards=64
 * </pre>
 *
 * <p>为什么要有它：三个优化（连接池 / 台账插入写法 / 分桶）分三次镜像上线，
 * 只要日志对不上，那一档压测数据就作废重来。宁可多一行噪音，也不要「以为跑的是新代码」。
 */
@Component
public class StockModeReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockModeReporter.class);

    private final StockStore stockStore;
    private final StockLedgerService ledgerService;
    private final StockProperties properties;

    public StockModeReporter(StockStore stockStore, StockLedgerService ledgerService, StockProperties properties) {
        this.stockStore = stockStore;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("stock.mode={} ledger={} idem-shards={}",
                stockStore.mode(), ledgerService.ledgerMode(), ledgerService.idemKeyShards());
        log.info("库存配置明细：bucket-enabled={} bucket-count={} ledger-insert-ignore={} idem-key-shards={} "
                        + "cache-refresh-ms={} cache-refresh-batch={}",
                properties.isBucketEnabled(), properties.getBucketCount(),
                properties.isLedgerInsertIgnore(), properties.getIdemKeyShards(),
                properties.getCacheRefreshMs(), properties.getCacheRefreshBatch());
    }
}
