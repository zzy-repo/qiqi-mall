package com.qiqi.mall.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 桶轮询顺序的单测（不连库、不起 Spring）。
 *
 * <p>它守的是扣减重试策略的两条硬要求：
 * <ol>
 *   <li><b>随机起点</b>：如果所有请求都从 0 号桶开始试，热点会从「1 把锁」变成「0 号桶这把锁」，
 *       拆桶等于白拆 —— 所以要能把起点挪到任意桶；</li>
 *   <li><b>同一次扣减不重复打同一个桶</b>：N 次重试必须恰好覆盖 N 个不同桶号各一次，
 *       否则失败语义会被「对同一个桶反复打」污染。</li>
 * </ol>
 */
class BucketRotationTest {

    @Test
    @DisplayName("顺序长度 = 桶数，且每个桶号恰好出现一次（不重复、不遗漏）")
    void sequenceCoversEveryBucketExactlyOnce() {
        for (int n = 1; n <= 16; n++) {
            final int size = n;
            for (int start = 0; start < n; start++) {
                final int startAt = start;
                int[] order = BucketRotation.sequence(size, startAt);
                assertThat(order).as("桶数 %d 起点 %d 的长度", size, startAt).hasSize(size);
                assertThat(Arrays.stream(order).distinct().count())
                        .as("桶数 %d 起点 %d：不许重复扣同一个桶", size, startAt).isEqualTo(size);
                assertThat(Arrays.stream(order).filter(x -> x < 0 || x >= size).count())
                        .as("桶数 %d 起点 %d：桶号必须在 [0,N)", size, startAt).isZero();
            }
        }
    }

    @Test
    @DisplayName("从给定起点开始循环递增（起点本身就是顺序的第一个）")
    void sequenceStartsAtGivenBucket() {
        assertThat(BucketRotation.sequence(8, 5)).containsExactly(5, 6, 7, 0, 1, 2, 3, 4);
        assertThat(BucketRotation.sequence(8, 0)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    @DisplayName("越界/负起点被规范到 [0,N)，不抛异常、不越界")
    void sequenceNormalisesOutOfRangeStart() {
        assertThat(BucketRotation.sequence(8, 8)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(BucketRotation.sequence(8, 11)).containsExactly(3, 4, 5, 6, 7, 0, 1, 2);
        assertThat(BucketRotation.sequence(8, -1)).containsExactly(7, 0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("桶数非法（<1）直接报错：绝不能悄悄退化成「一次都不试就判库存不足」")
    void sequenceRejectsNonPositiveBucketCount() {
        assertThatThrownBy(() -> BucketRotation.sequence(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("桶数");
    }

    @Test
    @DisplayName("所有起点都覆盖时，每个桶被选为「第一个被尝试的桶」的次数相等（流量真被摊平）")
    void everyBucketGetsItsShareAsFirstChoice() {
        int n = 8;
        long[] firstChoice = new long[n];
        for (int start : IntStream.range(0, n).toArray()) {
            firstChoice[BucketRotation.sequence(n, start)[0]]++;
        }
        assertThat(firstChoice).as("每个桶当第一选择的次数应相等 = 起点均匀时流量才均匀").containsOnly(1L);
    }
}
