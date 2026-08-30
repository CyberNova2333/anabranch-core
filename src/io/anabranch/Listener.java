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
     * 某条源在某一分片上失败了。{@code to} 目前恒为 null——下一条源要等下一轮
     * {@link Policy#pick} 才定，这里报的是「谁失败了」而不是「换成了谁」。
     * 想知道最终用了哪条，看 {@link #onChunkDone} 与日志。
     */
    default void onSourceSwitch(Source from, Source to, ChunkRef chunk, Throwable why) {}
    Listener NONE = new Listener() {};
}
