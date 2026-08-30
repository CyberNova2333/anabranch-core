package io.anabranch;

import java.io.File;

/** 一次下载请求。库内不存任何「包表」，调用方每次把要下什么说清楚。 */
public final class Request {
    /** 逻辑文件名，交给 {@link Source.Resolver} 换成各条源上的 URL。 */
    public final String key;
    public final File target;
    public final Integrity integrity;   // 可为 null
    public final int chunks;            // <=0 用 Downloader 的默认值
    public final boolean allowResume;

    public Request(String key, File target, Integrity integrity,
                   int chunks, boolean allowResume) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("key 为空");
        if (target == null) throw new IllegalArgumentException("target 为空");
        this.key = key;
        this.target = target;
        this.integrity = integrity;
        this.chunks = chunks;
        this.allowResume = allowResume;
    }

    public static Request of(String key, File target) {
        return new Request(key, target, null, 0, true);
    }
}
