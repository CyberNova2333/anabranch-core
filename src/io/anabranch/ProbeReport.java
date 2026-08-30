package io.anabranch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对所有源探测一遍的结果。
 *
 * <p>这件事比看上去重要：多源下载最阴的失败模式是<b>某条镜像还是旧版本</b>——
 * 每一块都「下载成功」，合起来是两个版本的拼接。逐块摘要能兜住，但那要等整轮
 * 下完才发现；先探一次能当场看出来。
 *
 * <h3>为什么尺寸不一致是错误，etag 不一致却只是提示</h3>
 * 同样的字节在不同服务端上 etag 完全可能不同——nginx 用 mtime+size，S3 用 md5，
 * 有的 CDN 自己再包一层。拿 etag 跨源比对会把好线路误判成坏的。<b>尺寸不同才是
 * 真的不同</b>（同尺寸不同内容仍然可能，那一层交给逐块摘要）。
 */
public final class ProbeReport {

    public final Map<String, Probe> ok;
    public final Map<String, String> failed;
    /** 所有成功探测的源尺寸是否一致。 */
    public final boolean sizesAgree;
    /** 一致时的尺寸，否则 -1。 */
    public final long size;
    /** 仅供参考，见类注释。 */
    public final boolean etagsAgree;

    ProbeReport(Map<String, Probe> ok, Map<String, String> failed) {
        this.ok = Collections.unmodifiableMap(new LinkedHashMap<>(ok));
        this.failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        long sz = -1; boolean sameSize = true, sameEtag = true;
        String etag = null;
        for (Probe p : this.ok.values()) {
            if (sz < 0) sz = p.total; else if (sz != p.total) sameSize = false;
            if (etag == null) etag = p.etag; else if (!etag.equals(p.etag)) sameEtag = false;
        }
        this.sizesAgree = sameSize;
        this.size = sameSize ? sz : -1;
        this.etagsAgree = sameEtag;
    }

    public boolean anyUsable() { return !ok.isEmpty(); }

    @Override public String toString() {
        return "ProbeReport{可用 " + ok.size() + " 条，失败 " + failed.size()
                + "，尺寸" + (sizesAgree ? "一致=" + size : "不一致") + "}";
    }
}
