package io.anabranch;

/**
 * 观察回调。全部是 default 空实现：调用方只覆盖关心的那几个。
 *
 * <p>{@code onSpeed} 的单位是<b>字节/秒</b>，不是 MB/s——单位换算是调用方的事，
 * 库里做只会让人猜是十进制 MB 还是 MiB。
 */
public interface Listener {
    default void onTotal(long total) {}
    default void onProgress(long soFar, long total) {}
    default void onSpeed(double bytesPerSec) {}
    default void onChunkDone(int index, long start, long endInclusive) {}
    default void onChunkVerifyFailed(int index, String expected, String actual) {}

    /**
     * {@code from} 在某一分片上失败、即将改用 {@code to} 重试时回调。
     *
     * <p>{@code to} 是下一次尝试<b>真正要用</b>的源：回调推迟到下一轮
     * {@link Policy#pick} 之后、重试开始之前才发（也因此发生在退避等待之后）。
     * {@code to} 可能等于 {@code from}——策略决定原地重试时挑回同一条是正常的。
     * 分片就此放弃（预算耗尽、次数用完、策略 ABORT、无源可用）时没有这个回调，
     * 因为没有发生切换——失败会以异常从 download 抛出。
     */
    default void onSourceSwitch(Source from, Source to, ChunkRef chunk, Throwable why) {}
    Listener NONE = new Listener() {};
}
