package io.anabranch;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * 基于 {@link HttpURLConnection} 的引擎——<b>零第三方依赖</b>。
 *
 * <p>不用 OkHttp 是刻意的：core 一旦引入依赖，使用者就要处理版本冲突，
 * 而这一层需要的东西（Range、超时、重定向）JDK 自带的就够。想换 OkHttp 的人
 * 自己实现一个 {@link Engine} 即可——这正是把 Engine 抽出来的意义。
 */
public final class HttpEngine implements Engine {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String userAgent;
    private final Logger log;

    public HttpEngine() { this(15000, 30000, "anabranch/0.1", Logger.NONE); }

    public HttpEngine(int connectTimeoutMs, int readTimeoutMs,
                      String userAgent, Logger log) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.userAgent = userAgent;
        this.log = log == null ? Logger.NONE : log;
    }

    @Override
    public Probe probe(String url, Cancellation c) throws IOException {
        // 先试 HEAD。有些 CDN 对 HEAD 返回 405 或不带 Content-Length，
        // 那时退化成「带 Range: bytes=0-0 的 GET」——这一招同时验证了 Range 支持，
        // 比先 HEAD 再单独试 Range 少一次往返。
        HttpURLConnection h = open(url, "HEAD");
        try {
            int code = h.getResponseCode();
            if (code / 100 == 2) {
                long len = contentLength(h);
                boolean range = "bytes".equalsIgnoreCase(h.getHeaderField("Accept-Ranges"));
                if (len > 0 && range) {
                    return new Probe(len, h.getHeaderField("ETag"), true);
                }
                // 没说支持 Range，不代表不支持——很多服务端只是不发这个头。往下探。
            }
        } catch (IOException ignored) {
            // HEAD 被拒是常见的，不值得当错误报
        } finally {
            h.disconnect();
        }
        return probeByRange(url);
    }

    /** 用 {@code Range: bytes=0-0} 探：206 + Content-Range 才算真支持。 */
    private Probe probeByRange(String url) throws IOException {
        HttpURLConnection h = open(url, "GET");
        h.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = h.getResponseCode();
            String cr = h.getHeaderField("Content-Range");
            if (code == 206 && cr != null) {
                // Content-Range: bytes 0-0/12345
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    String tail = cr.substring(slash + 1).trim();
                    if (!"*".equals(tail)) {
                        return new Probe(Long.parseLong(tail), h.getHeaderField("ETag"), true);
                    }
                }
            }
            if (code / 100 != 2) throw new IOException("探测失败，HTTP " + code + "：" + url);
            // 200：服务端无视了 Range，说明不支持分块。总长度只能取 Content-Length。
            return new Probe(contentLength(h), h.getHeaderField("ETag"), false);
        } finally {
            h.disconnect();
        }
    }

    @Override
    public void fetchRange(String url, long start, long endInclusive,
                           WritableByteChannel out, Listener l, Cancellation c)
            throws IOException {
        final long want = endInclusive - start + 1;
        HttpURLConnection h = open(url, "GET");
        h.setRequestProperty("Range", "bytes=" + start + "-" + endInclusive);
        try {
            int code = h.getResponseCode();
            if (code == 200 && start > 0) {
                // 服务端无视 Range 从头发。若照收就会把文件开头写进中间——静默损坏。
                throw new IOException("服务端忽略 Range（返回 200），拒绝按整份处理：" + url);
            }
            if (code != 206 && code != 200) {
                throw new IOException("HTTP " + code + "：" + url);
            }
            byte[] buf = new byte[64 * 1024];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            long got = 0;
            try (InputStream in = h.getInputStream()) {
                while (got < want) {
                    if (c.isCancelled()) throw new IOException("已取消");
                    int cap = (int) Math.min(buf.length, want - got);
                    int n = in.read(buf, 0, cap);
                    if (n < 0) break;
                    bb.limit(n).position(0);
                    while (bb.hasRemaining()) out.write(bb);
                    got += n;
                }
            }
            if (got != want) {
                // 少给了就是少给了。不抛的话上层会把不完整的块标成已完成。
                throw new IOException("区间长度不符：要 " + want + " 实收 " + got
                        + "（" + start + "-" + endInclusive + "）");
            }
        } finally {
            h.disconnect();
        }
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(connectTimeoutMs);
        h.setReadTimeout(readTimeoutMs);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", userAgent);
        h.setRequestProperty("Accept-Encoding", "identity"); // 压缩会让 Range 语义变味
        return h;
    }

    private static long contentLength(HttpURLConnection h) {
        String s = h.getHeaderField("Content-Length");
        try { return s == null ? -1 : Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}
