import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.anabranch.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 限速、滑动窗口测速、以及不可续传时的清理。
 *
 * <p>所有跟时间有关的断言**只取下界**。上界会因为机器负载而抖，而下界不会：
 * 限速只能让它更慢，不会让它更快。
 */
public class ThrottleTest {

    static int passed = 0, failed = 0;
    static byte[] BODY;
    static final AtomicBoolean SUPPORT_RANGE = new AtomicBoolean(true);
    static final AtomicBoolean CUT = new AtomicBoolean(false);

    static final int LIMIT = 200_000;      // 200 KB/s

    public static void main(String[] args) throws Exception {
        BODY = new byte[400_000];          // 限速下应当要 ~2 秒
        new Random(11).nextBytes(BODY);

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "srv"); t.setDaemon(true); return t; });
        srv.createContext("/f", ThrottleTest::serve);
        srv.setExecutor(pool);
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/f";
        File dir = Files.createTempDirectory("ab-throttle").toFile();

        try {
            long free = testUnlimitedIsFast(url, dir);
            long limited = testLimitIsEnforced(url, dir);
            check("限速确实比不限速慢得多", limited > free * 2,
                    "不限 " + free + "ms / 限速 " + limited + "ms");
            testLimitIsPerDownloadNotPerChunk(url, dir);
            testReportedSpeedTracksTheLimit(url, dir);
            testCancelDuringThrottleReturnsFast(url, dir);
            testNoRangeFailureLeavesNoJunk(url, dir);
        } finally {
            srv.stop(0);
            pool.shutdownNow();
        }
        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ───────────────────────── 用例 ─────────────────────────

    static long testUnlimitedIsFast(String url, File dir) throws Exception {
        File out = new File(dir, "free.bin");
        long t0 = System.nanoTime();
        dl(0, 4).download(new Request("f", out, null, 4, true), SourceSet.single(url),
                Listener.NONE, Cancellation.NONE);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("不限速能下完", same(out, BODY), "");
        return ms;
    }

    static long testLimitIsEnforced(String url, File dir) throws Exception {
        File out = new File(dir, "limited.bin");
        long t0 = System.nanoTime();
        dl(LIMIT, 1).download(new Request("f", out, null, 1, true), SourceSet.single(url),
                Listener.NONE, Cancellation.NONE);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("限速下内容仍然正确", same(out, BODY), "");
        // 400KB ÷ 200KB/s = 2 秒，减去桶里一开始就有的一秒额度 ≈ 1 秒。
        // 只取下界 800ms，留足余量。
        check("限速真的限住了", ms >= 800, "只用了 " + ms + "ms（期望 >=800）");
        return ms;
    }

    /** 限的是整次下载：分片数从 1 变 4，耗时不该缩到四分之一。 */
    static void testLimitIsPerDownloadNotPerChunk(String url, File dir) throws Exception {
        File out = new File(dir, "limited4.bin");
        long t0 = System.nanoTime();
        dl(LIMIT, 4).download(new Request("f", out, null, 4, true), SourceSet.single(url),
                Listener.NONE, Cancellation.NONE);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("四个分片下内容仍然正确", same(out, BODY), "");
        check("限速没有被分片数乘上去", ms >= 800,
                "四分片只用了 " + ms + "ms——限速可能变成了每分片一份");
    }

    /** onSpeed 报的应当是最近窗口的速率，因此会收敛到限速值附近。 */
    static void testReportedSpeedTracksTheLimit(String url, File dir) throws Exception {
        File out = new File(dir, "speed.bin");
        AtomicReference<Double> last = new AtomicReference<>(0.0);
        dl(LIMIT, 2).download(new Request("f", out, null, 2, true), SourceSet.single(url),
                new Listener() {
                    public void onSpeed(double bps) { if (bps > 0) last.set(bps); }
                }, Cancellation.NONE);
        double bps = last.get();
        check("报告过非零速度", bps > 0, "" + bps);
        // 带宽很宽：滑动窗口分辨率有限，桶里的一秒额度也会让开头偏快。
        // 这条要验的是「量级对不对」，不是精度。
        check("报告的速度落在限速的量级上", bps > LIMIT * 0.2 && bps < LIMIT * 4,
                String.format("报了 %.0f B/s，限速 %d B/s", bps, LIMIT));
    }

    /** 限速等待期间取消，应当很快返回，而不是等到整份传完。 */
    static void testCancelDuringThrottleReturnsFast(String url, File dir) throws Exception {
        File out = new File(dir, "cancel.bin");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            cancelled.set(true);
        }, "canceller").start();

        long t0 = System.nanoTime();
        boolean threw = false;
        try {
            // 限得很死：不取消的话要 40 秒
            dl(10_000, 1).download(new Request("f", out, null, 1, true), SourceSet.single(url),
                    Listener.NONE, cancelled::get);
        } catch (IOException e) { threw = true; }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("取消会抛错而不是悄悄成功", threw, "");
        check("限速等待期间能及时响应取消", ms < 5000,
                "等了 " + ms + "ms（不取消要 40 秒，说明没在等待里检查取消）");
    }

    /** 服务端不支持 Range 时，失败不该留下永远用不上的 .part/.meta。 */
    static void testNoRangeFailureLeavesNoJunk(String url, File dir) throws Exception {
        SUPPORT_RANGE.set(false);
        CUT.set(true);
        File out = new File(dir, "norange-fail.bin");
        try {
            dl(0, 4).download(new Request("f", out, null, 4, true), SourceSet.single(url),
                    Listener.NONE, Cancellation.NONE);
        } catch (IOException ignored) {
        } finally {
            SUPPORT_RANGE.set(false);
            CUT.set(false);
        }
        check("不可续传时失败不留 .part", !new File(out.getPath() + ".part").exists(), "");
        check("不可续传时失败不留 .meta", !new File(out.getPath() + ".meta").exists(), "");

        // 对照：支持 Range 时失败**应当**留下 .meta 供续传
        SUPPORT_RANGE.set(true);
        CUT.set(true);
        File out2 = new File(dir, "range-fail.bin");
        try {
            dl(0, 4).download(new Request("f", out2, null, 4, true), SourceSet.single(url),
                    Listener.NONE, Cancellation.NONE);
        } catch (IOException ignored) {
        } finally {
            CUT.set(false);
        }
        check("可续传时失败仍然留下 .meta", new File(out2.getPath() + ".meta").exists(),
                "把该留的也删了就是过度清理");
    }

    // ───────────────────────── 支撑 ─────────────────────────

    static Downloader dl(long limit, int chunks) {
        return Downloader.newBuilder()
                .engine(new HttpEngine(2000, 3000, "anabranch-test", Logger.NONE))
                .defaultChunks(chunks).maxConcurrent(Math.min(4, chunks))
                .bytesPerSecond(limit)
                .build();
    }

    static void serve(HttpExchange x) throws IOException {
        String range = x.getRequestHeaders().getFirst("Range");
        x.getResponseHeaders().add("ETag", "\"v1\"");
        if (SUPPORT_RANGE.get()) x.getResponseHeaders().add("Accept-Ranges", "bytes");
        if ("HEAD".equals(x.getRequestMethod())) {
            x.sendResponseHeaders(200, -1); x.close(); return;
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
            x.getResponseHeaders().add("Content-Range",
                    "bytes " + from + "-" + to + "/" + BODY.length);
            x.sendResponseHeaders(206, len);
        } else {
            x.sendResponseHeaders(200, len);
        }
        try (OutputStream os = x.getResponseBody()) {
            os.write(BODY, from, CUT.get() ? Math.min(len, 500) : len);
        } catch (IOException ignored) {}
        x.close();
    }

    static boolean same(File f, byte[] want) throws IOException {
        if (!f.isFile() || f.length() != want.length) return false;
        byte[] got = new byte[(int) f.length()];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.readFully(got); }
        return Arrays.equals(got, want);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else { failed++; System.out.println("  ✘ " + name + (detail.isEmpty() ? "" : "  —— " + detail)); }
    }
}
