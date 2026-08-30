package io.anabranch;

import java.io.File;

/** 一次下载请求。库内不存任何「包表」，调用方每次把要下什么说清楚。 */
public final class Request {
    public final String url;
    public final File target;
    public final Integrity integrity;   // 可为 null
    public final int chunks;            // <=0 用 Downloader 的默认值
    public final boolean allowResume;

    public Request(String url, File target, Integrity integrity,
                   int chunks, boolean allowResume) {
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("url 为空");
        if (target == null) throw new IllegalArgumentException("target 为空");
        this.url = url;
        this.target = target;
        this.integrity = integrity;
        this.chunks = chunks;
        this.allowResume = allowResume;
    }

    public static Request of(String url, File target) {
        return new Request(url, target, null, 0, true);
    }
}
