package io.anabranch.aria2;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

import io.anabranch.Cancellation;
import io.anabranch.Engine;
import io.anabranch.Listener;
import io.anabranch.Probe;

/**
 * 形态 2（实验）：以 {@link Engine} 形态接入，让 anabranch 自己的调度配 native 传输。
 *
 * <p><b>存亡取决于一条实测</b>（docs/ARIA2.md §八）：aria2 自己管分段，不提供
 * 「下载指定字节区间」的选项；只有当「{@code split=1} + 单连接 + 自定义 Range 头」
 * 在目标 aria2 版本上被证实行为可靠时，这个类才有实现的资格。<b>实测不过就删掉
 * 这个类</b>，不留半活的路径——基线形态是 {@link Aria2Downloader}。
 *
 * <p>注意 Engine 实现的分层铁律：这里不许引用调度层
 * （Downloader / Policy / SourceSet / ResumeState / Request / Stats）。
 */
public final class Aria2Engine implements Engine {

    @Override public Probe probe(String url, Cancellation c) throws IOException {
        // 计划：probe 不走 aria2——HEAD 一次拿 total/etag/rangeSupported 用
        // HttpURLConnection 就够，没必要为一次探测起 native session。
        throw new UnsupportedOperationException("实验形态，待 Range 实测后决定去留");
    }

    @Override public void fetchRange(String url, long start, long endInclusive,
                                     WritableByteChannel out, Listener l, Cancellation c)
            throws IOException {
        throw new UnsupportedOperationException("实验形态，待 Range 实测后决定去留");
    }
}
