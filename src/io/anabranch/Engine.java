package io.anabranch;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * 下载引擎：只负责「把一个字节区间取回来」。
 *
 * <p>分片怎么划、失败怎么重试、什么时候换源，<b>全都不在这一层</b>——那些是
 * {@link Downloader} 的事。引擎只要能诚实地完成或诚实地抛异常就够了。
 *
 * <p>这条边界是整个库能同时支持「纯 Java HTTP」和「native aria2」的原因：
 * 两者对「取一段字节」都能实现，而对调度的看法完全不同。把调度留在引擎里，
 * 换引擎就等于换掉整套重试与切源策略——那正是原实现里最难改的地方。
 */
public interface Engine {

    /**
     * 探测一个 URL。实现应尽量用一次轻量请求拿到总长度、etag 与是否支持 Range。
     *
     * @throws IOException 网络失败，或响应不足以判断（由调用方决定退化还是放弃）
     */
    Probe probe(String url, Cancellation c) throws IOException;

    /**
     * 取 [start, endInclusive] 这一段，<b>顺序</b>写进 out。
     *
     * <p>实现<b>不</b>负责定位：out 已经指向该区间在目标文件里的正确位置
     * （见 {@code Downloader.PositionedChannel}）。实现只管从头写到尾。
     *
     * <p>实现<b>必须</b>周期性检查 {@code c.isCancelled()} 并及时返回——
     * 否则取消一个大文件要等到整块下完，用户会以为卡死了。
     *
     * @throws IOException 含「服务端不认 Range」「返回长度与请求不符」等情况；
     *                     不要静默截断，那会让上层把半块当成整块提交
     */
    void fetchRange(String url, long start, long endInclusive,
                    WritableByteChannel out, Listener l, Cancellation c)
            throws IOException;
}
