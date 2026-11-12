package com.qiqi.mall.stock;

/**
 * 桶的轮询顺序：从随机起点开始，把 N 个桶号各走一遍（每个桶号恰好出现一次）。
 *
 * <p>为什么需要「随机起点」：如果所有请求都从 0 号桶开始试，热点就会从「1 把锁」变成
 * 「0 号桶这把锁」—— 并发全挤在第一个桶上，等于没拆。随机起点把流量摊到 N 把锁上。
 *
 * <p>为什么必须是「各出现一次」的排列：一次扣减里同一个桶只许试一次，
 * 否则「重试 N 次」就可能对同一个桶反复打，既浪费又把失败语义搞乱
 * （同一个桶第二次必然也是 0 行，除非中间有人回补 —— 那不是我们想要的语义）。
 *
 * <p>独立成一个小类是为了能脱离数据库单测（见 {@code SkuBucketRotationTest}）。
 */
public final class BucketRotation {

    private BucketRotation() {
    }

    /**
     * @param bucketCount 桶数 N（&gt;= 1）
     * @param start       起点桶号（会被规范到 [0, N)）
     * @return 长度为 N 的桶号排列，从 start 开始循环递增，每号恰好一次
     */
    public static int[] sequence(int bucketCount, int start) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("桶数必须 >= 1，实际 " + bucketCount);
        }
        int base = Math.floorMod(start, bucketCount);
        int[] order = new int[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            order[i] = (base + i) % bucketCount;
        }
        return order;
    }
}
