import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.anabranch.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 单源分块下载器的端到端测试：真起一个 HTTP 服务器，真下文件。 */
public class SingleSourceTest {

    static int passed = 0, failed = 0;
    static byte[] BODY;
    static String ETAG = "\"v1\"";
    static final AtomicBoolean SUPPORT_RANGE = new AtomicBoolean(true);
    static final AtomicInteger SERVED = new AtomicInteger();
    /** >0 时：每个 Range 请求只发这么多字节就断开，用来模拟中途失败。 */
    static final AtomicInteger CUT_AFTER = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        BODY = new byte[3_000_000];
        new Random(42).nextBytes(BODY);

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/file.bin", SingleSourceTest::serve);
        // 必须用守护线程：HttpServer.stop() 不会关掉调用方给的 ExecutorService，
        // 用普通线程池的话 JVM 到最后退不出去，表现是「测试跑完了但进程不结束」。
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(8, rn -> {
                    Thread t = new Thread(rn, "srv"); t.setDaemon(true); return t; });
        srv.setExecutor(pool);
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/file.bin";
        File dir = Files.createTempDirectory("anabranch").toFile();

        try {
            testProbe(url);
            testPlainDownload(url, dir);
            testResumeAfterFailure(url, dir);
            testChunkVerifyCatchesCorruption(url, dir);
            testEtagChangeDiscardsResume(url, dir);
            testNoRangeServer(url, dir);
        } finally {
            srv.stop(0);
            pool.shutdownNow();
        }
        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ───────────────────────── 用例 ─────────────────────────

    static void testProbe(String url) throws Exception {
        Probe p = new HttpEngine().probe(url, Cancellation.NONE);
        check("探测总长度", p.total == BODY.length, "得到 " + p.total);
        check("探测 Range 支持", p.rangeSupported, "得到 " + p.rangeSupported);
        check("探测 etag", ETAG.equals(p.etag), "得到 " + p.etag);
    }

    static void testPlainDownload(String url, File dir) throws Exception {
        File out = new File(dir, "plain.bin");
        List<Long> progress = new ArrayList<>();
        Result r = dl(8).download(
                new Request(url, out, null, 8, true),
                new Listener() {
                    public void onProgress(long soFar, long total) { progress.add(soFar); }
                }, Cancellation.NONE);
        check("整份下载内容一致", sameBytes(out, BODY), "长度 " + out.length());
        check("Result.totalBytes", r.totalBytes == BODY.length, "" + r.totalBytes);
        check("进度回调有推进", progress.size() > 2, "回调 " + progress.size() + " 次");
        check("下完不留 .part/.meta",
                !new File(out.getPath() + ".part").exists()
                        && !new File(out.getPath() + ".meta").exists(), "");
    }

    /** 服务端中途断开 → 第一次必然失败；第二次应当只补没下完的块。 */
    static void testResumeAfterFailure(String url, File dir) throws Exception {
        File out = new File(dir, "resume.bin");
        CUT_AFTER.set(1000);                 // 每块只发 1000 字节就断
        boolean threw = false;
        try {
            dl(6).download(new Request(url, out, null, 6, true),
                    Listener.NONE, Cancellation.NONE);
        } catch (IOException e) { threw = true; }
        CUT_AFTER.set(0);
        check("中途断开会抛错而不是悄悄成功", threw, "");
        check("失败后留下 .meta 供续传", new File(out.getPath() + ".meta").exists(), "");

        Result r = dl(6).download(new Request(url, out, null, 6, true),
                Listener.NONE, Cancellation.NONE);
        check("续传后内容一致", sameBytes(out, BODY), "长度 " + out.length());
        check("续传结果长度正确", r.totalBytes == BODY.length, "" + r.totalBytes);
    }

    /** 逐块摘要里塞一个错的 → 必须报出来，而且报的是那一块。 */
    static void testChunkVerifyCatchesCorruption(String url, File dir) throws Exception {
        long unit = 512 * 1024;
        int n = (int) ((BODY.length + unit - 1) / unit);
        String[] hashes = new String[n];
        for (int i = 0; i < n; i++) {
            int s = (int) (i * unit), e = (int) Math.min(BODY.length, s + unit);
            hashes[i] = sha256(BODY, s, e - s);
        }
        String good = hashes[2];
        hashes[2] = "00".repeat(32);          // 投毒

        File out = new File(dir, "verify.bin");
        final int[] badIndex = {-1};
        boolean threw = false;
        try {
            dl(4).download(
                    new Request(url, out, new Integrity(unit, hashes, null), 0, true),
                    new Listener() {
                        public void onChunkVerifyFailed(int i, String w, String g) { badIndex[0] = i; }
                    }, Cancellation.NONE);
        } catch (IOException e) { threw = true; }
        check("逐块校验能拦下坏块", threw, "");
        check("报出来的是第 2 块", badIndex[0] == 2, "报的是 " + badIndex[0]);
        check("坏块不会被提交成成品", !out.exists(), "");

        hashes[2] = good;                      // 换回真摘要，应当能下完
        Result r = dl(4).download(
                new Request(url, out, new Integrity(unit, hashes, sha256(BODY, 0, BODY.length)), 0, true),
                Listener.NONE, Cancellation.NONE);
        check("摘要正确时能下完", sameBytes(out, BODY), "");
        check("Result.chunkVerified 为真", r.chunkVerified, "" + r.chunkVerified);
    }

    /** 服务端换了文件（etag 变） → 旧续传状态必须整份作废，不能接着写。 */
    static void testEtagChangeDiscardsResume(String url, File dir) throws Exception {
        File out = new File(dir, "etag.bin");
        CUT_AFTER.set(1000);
        try { dl(6).download(new Request(url, out, null, 6, true),
                Listener.NONE, Cancellation.NONE); } catch (IOException ignored) {}
        CUT_AFTER.set(0);
        check("有残留的续传状态", new File(out.getPath() + ".meta").exists(), "");

        ETAG = "\"v2\"";                        // 服务端换了内容
        try {
            Result r = dl(6).download(new Request(url, out, null, 6, true),
                    Listener.NONE, Cancellation.NONE);
            check("etag 变了仍能正确下完", sameBytes(out, BODY), "");
            check("下载量说明是整份重下而非续传",
                    r.transferredBytes == BODY.length, "实传 " + r.transferredBytes);
        } finally { ETAG = "\"v1\""; }
    }

    /** 服务端不支持 Range → 退化成整份单流下载，不能报错。 */
    static void testNoRangeServer(String url, File dir) throws Exception {
        SUPPORT_RANGE.set(false);
        try {
            File out = new File(dir, "norange.bin");
            Result r = dl(8).download(new Request(url, out, null, 8, true),
                    Listener.NONE, Cancellation.NONE);
            check("不支持 Range 时能整份下完", sameBytes(out, BODY), "长度 " + out.length());
            check("不支持 Range 时长度正确", r.totalBytes == BODY.length, "" + r.totalBytes);
        } finally { SUPPORT_RANGE.set(true); }
    }

    // ───────────────────────── 支撑 ─────────────────────────

    static Downloader dl(int chunks) {
        return Downloader.newBuilder().defaultChunks(chunks).maxConcurrent(4).build();
    }

    static void serve(HttpExchange x) throws IOException {
        SERVED.incrementAndGet();
        String range = x.getRequestHeaders().getFirst("Range");
        x.getResponseHeaders().add("ETag", ETAG);
        if (SUPPORT_RANGE.get()) x.getResponseHeaders().add("Accept-Ranges", "bytes");

        if ("HEAD".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Content-Length", String.valueOf(BODY.length));
            x.sendResponseHeaders(200, -1);
            x.close();
            return;
        }
        int from = 0, to = BODY.length - 1;
        boolean partial = false;
        if (range != null && SUPPORT_RANGE.get() && range.startsWith("bytes=")) {
            String[] p = range.substring(6).split("-");
            from = Integer.parseInt(p[0]);
            to = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) : BODY.length - 1;
            partial = true;
        }
        int len = to - from + 1;
        if (partial) {
            x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + BODY.length);
            x.sendResponseHeaders(206, len);
        } else {
            x.sendResponseHeaders(200, len);
        }
        int cut = CUT_AFTER.get();
        try (OutputStream os = x.getResponseBody()) {
            int send = cut > 0 ? Math.min(len, cut) : len;
            os.write(BODY, from, send);
        } catch (IOException ignored) {
        }
        x.close();
    }

    static boolean sameBytes(File f, byte[] want) throws IOException {
        if (!f.isFile() || f.length() != want.length) return false;
        byte[] got = new byte[(int) f.length()];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.readFully(got); }
        return java.util.Arrays.equals(got, want);
    }

    static String sha256(byte[] b, int off, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(b, off, len);
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest()) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else { failed++; System.out.println("  ✘ " + name + (detail.isEmpty() ? "" : "  —— " + detail)); }
    }
}
