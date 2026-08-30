package io.anabranch;

/**
 * 滑动窗口测速。
 *
 * <p>之前 {@code onSpeed} 报的是「开始到现在的平均」。那个数在界面上是误导的：
 * 下载了十分钟之后突然断流，平均速度还是很好看，用户看到的进度条却不动了。
 * 想知道「现在快不快」，就得只看最近这一小段。
 *
 * <h3>为什么用分桶而不是存每个样本</h3>
 * 存样本要按时间裁剪，样本多了裁剪本身就成了负担，而这个方法在每写一次数据时都会
 * 被调用。分桶是定长的：{@value #BUCKETS} 个桶轮转，每个桶一段固定时长，
 * 写入是 O(1)，读取是遍历固定个数的桶。
 *
 * <p>代价是分辨率只到一个桶的宽度，而这对「给人看的速度」完全够用。
 */
final class SpeedMeter {

    /** 窗口总长（毫秒）。取 3 秒：短到能反映变化，长到不会因为一次 TCP 抖动就跳。 */
    private static final long WINDOW_MS = 3000;
    private static final int BUCKETS = 12;
    private static final long BUCKET_MS = WINDOW_MS / BUCKETS;

    private final long[] bytes = new long[BUCKETS];
    private final long[] stamp = new long[BUCKETS];   // 该桶所属的时间片编号

    synchronized void record(long n) {
        if (n <= 0) return;
        long slot = System.currentTimeMillis() / BUCKET_MS;
        int i = (int) Math.floorMod(slot, BUCKETS);
        if (stamp[i] != slot) {        // 轮到复用这个桶了，先清掉上一圈的残留
            stamp[i] = slot;
            bytes[i] = 0;
        }
        bytes[i] += n;
    }

    /** 最近 {@value #WINDOW_MS} 毫秒的平均速率（字节/秒）；没有样本时返回 0。 */
    synchronized double bytesPerSec() {
        long slot = System.currentTimeMillis() / BUCKET_MS;
        long sum = 0;
        int used = 0;
        for (int i = 0; i < BUCKETS; i++) {
            // 只算还在窗口内的桶。stamp 落在窗口外的是上一圈的旧数据。
            if (stamp[i] > slot - BUCKETS && stamp[i] <= slot) {
                sum += bytes[i];
                used++;
            }
        }
        if (used == 0 || sum == 0) return 0;
        // 分母用整个窗口而不是「有数据的桶数」：中间空了几个桶恰恰说明那几段没传，
        // 按有数据的桶算会把断流期间的速度算得虚高。
        return sum * 1000.0 / WINDOW_MS;
    }
}
