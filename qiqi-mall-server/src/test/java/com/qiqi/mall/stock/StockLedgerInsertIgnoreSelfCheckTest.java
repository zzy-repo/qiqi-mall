package com.qiqi.mall.stock;

import com.qiqi.mall.stock.entity.StockLedger;
import com.qiqi.mall.stock.mapper.StockLedgerMapper;
import com.qiqi.mall.stock.service.StockLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code INSERT IGNORE} 那条「0 行时回查」自检的单测（不连库、纯 Mockito）。
 *
 * <h3>它守的那条底线</h3>
 * {@code INSERT IGNORE} 会吞掉**所有**错误（不止唯一键冲突）：字段超长、NOT NULL 违反……
 * 都会静默变成「0 行」。如果服务层把 0 行一律当成「重放」，就等于
 * <b>账没记上、却告诉自己记过了</b> —— 这比死锁更毒（死锁至少会失败重试，这个是静默丢账）。
 *
 * <p>所以判据必须是两条：
 * <ol>
 *   <li>0 行 <b>且</b> 按幂等键回查得到 1 行 → 才算重放（返回 true，库存不动）；</li>
 *   <li>0 行但回查不到 → 抛异常（fail-closed），绝不当重放。</li>
 * </ol>
 */
class StockLedgerInsertIgnoreSelfCheckTest {

    private final StockLedgerMapper ledgerMapper = mock(StockLedgerMapper.class);
    private final StockStore stockStore = mock(StockStore.class);
    private final StockProperties properties = new StockProperties();

    private StockLedgerService service() {
        properties.setLedgerInsertIgnore(true);   // 被测写法
        return new StockLedgerService(ledgerMapper, stockStore, properties);
    }

    @AfterEach
    void clearContext() {
        StockLedgerContext.close(null);
    }

    @Test
    @DisplayName("0 行但幂等键回查不到 → 抛异常（不能把「被 IGNORE 吞掉的错误」当成重放）")
    void swallowedErrorIsNotTreatedAsReplay() {
        when(ledgerMapper.insertIgnore(anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(0);
        when(ledgerMapper.countByIdemKey(anyString())).thenReturn(0);

        StockLedgerContext.open(1L, StockLedger.REASON_ORDER_CREATE, null, null);

        assertThatThrownBy(() -> service().deduct(7L, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IGNORE");
        verify(stockStore, never()).deduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("0 行且幂等键确实存在 → 按重放处理：返回 true，库存一件都不动")
    void duplicateIsTreatedAsReplay() {
        when(ledgerMapper.insertIgnore(anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(0);
        when(ledgerMapper.countByIdemKey(anyString())).thenReturn(1);

        StockLedgerContext.open(1L, StockLedger.REASON_ORDER_CREATE, null, null);

        assertThat(service().deduct(7L, 1)).as("重放 = 已扣过，返回 true（与 baseline 判据一致）").isTrue();
        verify(stockStore, never()).deduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("插入成功（1 行）→ 走扣减，并把落点桶号回填进台账")
    void insertedRowDeductsAndAttachesBucket() {
        when(ledgerMapper.insertIgnore(anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(stockStore.usesBuckets()).thenReturn(true);
        when(stockStore.deduct(7L, 2)).thenReturn(StockStore.StockWrite.ok(5));

        StockLedgerContext.open(1L, StockLedger.REASON_ORDER_CREATE, null, null);

        assertThat(service().deduct(7L, 2)).isTrue();
        verify(stockStore).deduct(7L, 2);
        verify(ledgerMapper).attachBucketNo(anyString(), eq(5));
    }

    @Test
    @DisplayName("legacy 路径（不使用桶）→ 不查询「原桶」也不回填桶号")
    void legacyModeNeverTouchesBucketNo() {
        when(ledgerMapper.insertIgnore(anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(stockStore.usesBuckets()).thenReturn(false);
        when(stockStore.deduct(7L, 1)).thenReturn(StockStore.StockWrite.ok(null));

        StockLedgerContext.open(1L, StockLedger.REASON_ORDER_CREATE, null, null);

        assertThat(service().deduct(7L, 1)).isTrue();
        verify(ledgerMapper, never()).attachBucketNo(anyString(), anyInt());
        verify(ledgerMapper, never()).findDeductBucketNo(anyLong(), anyLong());
    }

    @Test
    @DisplayName("库存不足 → 撤掉刚记的台账行（不留「账上扣了、库存没扣」的假账）")
    void insufficientStockRollsBackLedgerRow() {
        when(ledgerMapper.insertIgnore(anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(stockStore.deduct(7L, 5)).thenReturn(StockStore.StockWrite.insufficient());

        StockLedgerContext.open(1L, StockLedger.REASON_ORDER_CREATE, null, null);

        assertThat(service().deduct(7L, 5)).isFalse();
        verify(ledgerMapper).deleteByIdemKey(anyString());
    }
}
