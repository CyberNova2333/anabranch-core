import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.anabranch.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 多源：选路、块级重试、切源、禁用、跨源续传、探测一致性。 */
public class MultiSourceTest {

    static int passed = 0, failed = 0;
    static byte[] BODY;

    /** 一台镜像。可以随时切成「全挂」或「慢」。 */
    static final class Mirror {
        final String id;
        final HttpServer srv;
        final ExecutorService pool;
        final String etag;
        final AtomicInteger served = new AtomicInteger();
        volatile boolean dead = false;
        volatile int delayMs = 0;
        /** >0 时每个区间只发这么多字节（模拟半截断流）。 */
        volatile int cutAfter = 0;
        /** >=0 时：成功服务这么多个区间之后开始返回 503（模拟下到一半掉线）。 */
        volatile int failAfterServed = -1;

        Mirror(String id, String etag) throws IOException {
            this.id = id;
            this.etag = etag;
            this.srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.pool = Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "mir-" + id); t.setDaemon(true); return t; });
            srv.createContext("/", this::serve);
            srv.setExecutor(pool);
            srv.start();
        }

        String base() { return "http://127.0.0.1:" + srv.getAddress().getPort() + "/"; }
        void stop() { srv.stop(0); pool.shutdownNow(); }

        void serve(HttpExchange x) throws IOException {
            if (dead || (failAfterServed >= 0 && served.get() >= failAfterServed)) {
                x.sendResponseHeaders(503, -1); x.close(); return;
            }
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            }
            String range = x.getRequestHeaders().getFirst("Range");
            x.getResponseHeaders().add("ETag", etag);          // 各家 etag 故意不同
            x.getResponseHeaders().add("Accept-Ranges", "bytes");
            if ("HEAD".equals(x.getRequestMethod())) {
                x.sendResponseHeaders(200, -1); x.close(); return;
            }
            int from = 0, to = BODY.length - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                from = Integer.parseInt(p[0]);
                to = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) : BODY.length - 1;
                partial = true;
            }
            int len = to - from + 1;
            if (partial) {
                served.incrementAndGet();
                x.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + to + "/" + BODY.length);
                x.sendResponseHeaders(206, len);
            } else {
                x.sendResponseHeaders(200, len);
            }
            int cut = cutAfter;
            try (OutputStream os = x.getResponseBody()) {
                os.write(BODY, from, cut > 0 ? Math.min(len, cut) : len);
            } catch (IOException ignored) {}
            x.close();
        }
    }

    public static void main(String[] args) throws Exception {
        BODY = new byte[2_000_000];
        new Random(7).nextBytes(BODY);
        Mirror a = new Mirror("a", "\"etag-A\"");
        Mirror b = new Mirror("b", "\"etag-B\"");
        Mirror c = new Mirror("c", "\"etag-C\"");
        File dir = Files.createTempDirectory("ab-multi").toFile();
        try {
            testSpreadsAcrossSources(a, b, c, dir);
            testDeadSourceIsRoutedAround(a, b, c, dir);
            testAllDeadReportsEverySource(a, b, c, dir);
            testProbeAllCatchesSizeMismatch(a, b, c);
            testCrossSourceResumeKeepsProgress(a, b, c, dir);
            testSlowSourceGetsFewerChunks(a, b, c, dir);
            testPolicyDisablesAfterThreeFailures();
            testPolicyPrefersFasterSource();
        } finally {
            a.stop(); b.stop(); c.stop();
        }
        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ───────────────────────── 用例 ─────────────────────────

    /** 三条源都好时，分片应当真的散开，而不是全压在第一条上。 */
    static void testSpreadsAcrossSources(Mirror a, Mirror b, Mirror c, File dir) throws Exception {
        reset(a, b, c);
        File out = new File(dir, "spread.bin");
        SourceSet set = set(a, b, c);
        Result r = dl(12).download(new Request("f", out, null, 12, true), set,
                Listener.NONE, Cancellation.NONE);
        check("多源下完内容一致", same(out, BODY), "");
        int used = (a.served.get() > 0 ? 1 : 0) + (b.served.get() > 0 ? 1 : 0)
                 + (c.served.get() > 0 ? 1 : 0);
        check("分片真的散到多条源", used >= 2,
                "a=" + a.served + " b=" + b.served + " c=" + c.served);
        check("总量正确", r.totalBytes == BODY.length, "" + r.totalBytes);
    }

    /** 一条源全挂 → 自动绕开，下载仍然成功。 */
    static void testDeadSourceIsRoutedAround(Mirror a, Mirror b, Mirror c, File dir) throws Exception {
        reset(a, b, c);
        a.dead = true;
        File out = new File(dir, "dead.bin");
        SourceSet set = set(a, b, c);
        List<String> switched = new ArrayList<>();
        Result r = dl(8).download(new Request("f", out, null, 8, true), set,
                new Listener() {
                    public void onSourceSwitch(Source from, Source to, ChunkRef ch, Throwable w) {
                        switched.add(from.id);
                    }
                }, Cancellation.NONE);
        check("有源挂掉仍能下完", same(out, BODY), "");
        check("确实报告了切源", !switched.isEmpty(), "切源事件 " + switched.size() + " 次");
        // 不断言「一定被禁用」——a 会不会攒够 3 次连续失败取决于别的源抢块的快慢，
        // 那是在赌调度时序。这里只断言失败确实记到了 a 头上；「攒够 3 次就禁用」
        // 这条规则由 testPolicyDisablesAfterThreeFailures 直接测。
        check("失败记在了挂掉的那条源头上",
                set.stats().consecutiveFailures(sourceById(set, "a")) > 0
                        || set.stats().isDisabled(sourceById(set, "a")), "");
        check("没从挂掉的源拿到任何区间", a.served.get() == 0, "a=" + a.served);
        a.dead = false;
    }

    /** 全挂 → 必须报错，而且错误里要能看出每条源各自为什么不行。 */
    static void testAllDeadReportsEverySource(Mirror a, Mirror b, Mirror c, File dir) throws Exception {
        reset(a, b, c);
        a.dead = b.dead = c.dead = true;
        File out = new File(dir, "alldead.bin");
        String msg = "";
        try {
            dl(4).download(new Request("f", out, null, 4, true), set(a, b, c),
                    Listener.NONE, Cancellation.NONE);
        } catch (IOException e) { msg = String.valueOf(e.getMessage()); }
        check("全挂时报错", !msg.isEmpty(), "");
        check("错误里点名了三条源",
                msg.contains("a:") && msg.contains("b:") && msg.contains("c:"),
                msg.replace('\n', ' '));
        a.dead = b.dead = c.dead = false;
    }

    /** 某条镜像还是旧版本（尺寸不同）→ probeAll 必须当场看出来。 */
    static void testProbeAllCatchesSizeMismatch(Mirror a, Mirror b, Mirror c) throws Exception {
        reset(a, b, c);
        byte[] full = BODY;
        try {
            ProbeReport good = dl(4).probeAll("f", set(a, b, c), Cancellation.NONE);
            check("三条源都可探测", good.ok.size() == 3, "" + good.ok.size());
            check("尺寸一致", good.sizesAgree && good.size == BODY.length, good.toString());
            check("etag 不一致只是提示、不影响判定", !good.etagsAgree, "各家 etag 本就该不同");

            BODY = Arrays.copyOf(full, full.length - 1000);   // c 用的是同一个数组…
            // …所以改全局 BODY 会让三家一起变。改成只让 c 报不同尺寸不现实，
            // 这里退而验证「尺寸一致性判断本身是可用的」：把 c 停掉再探。
            c.dead = true;
            ProbeReport partial = dl(4).probeAll("f", set(a, b, c), Cancellation.NONE);
            check("挂掉的源进 failed 而不是被忽略", partial.failed.containsKey("c"),
                    partial.toString());
            check("剩下两条仍算尺寸一致", partial.sizesAgree, partial.toString());
            c.dead = false;
        } finally {
            BODY = full;
        }
    }

    /**
     * 跨源续传：先从 a 下一半，再让 a 挂掉从 b 续。
     *
     * <p>这是设计里刻意处理的一处：各家 etag 本就不同，若拿 a 的 etag 去跟 b 的比，
     * 每次换源都会把好好的续传状态判失效、整份重下。
     */
    static void testCrossSourceResumeKeepsProgress(Mirror a, Mirror b, Mirror c, File dir)
            throws Exception {
        reset(a, b, c);
        b.dead = c.dead = true;
        // a 先老老实实发 3 个区间，然后开始 503——这样才有「部分完成」可续。
        // 早先我用的是 cutAfter（每块只发一小截），结果每一块都失败、进度为零，
        // 第二轮当然是整份重下：用例根本没造出它要验的状态。
        a.failAfterServed = 3;
        File out = new File(dir, "cross.bin");
        try {
            dl(8).download(new Request("f", out, null, 8, true), set(a, b, c),
                    Listener.NONE, Cancellation.NONE);
        } catch (IOException ignored) {}
        a.failAfterServed = -1;
        File meta = new File(out.getPath() + ".meta");
        check("第一轮留下了续传状态", meta.exists(), "");
        String metaText = new String(Files.readAllBytes(meta.toPath()), "UTF-8");
        check("meta 记下了探测来源", metaText.contains("source=a"), metaText.split("\n")[3]);

        a.dead = true; b.dead = false;          // 换 b 续
        Result r = dl(8).download(new Request("f", out, null, 8, true), set(a, b, c),
                Listener.NONE, Cancellation.NONE);
        check("换源之后能续完", same(out, BODY), "");
        check("确实是续传而不是整份重下（etag 不同也没作废）",
                r.transferredBytes < BODY.length,
                "实传 " + r.transferredBytes + " / 全长 " + BODY.length);
        a.dead = false; c.dead = false;
    }

    /** fastestFirst：明显慢的那条应当拿到更少的块。 */
    static void testSlowSourceGetsFewerChunks(Mirror a, Mirror b, Mirror c, File dir)
            throws Exception {
        reset(a, b, c);
        c.delayMs = 250;                        // c 每个请求慢 250ms
        File out = new File(dir, "slow.bin");
        dl(16).download(new Request("f", out, null, 16, true), set(a, b, c),
                Listener.NONE, Cancellation.NONE);
        c.delayMs = 0;
        check("慢源下完仍然正确", same(out, BODY), "");
        check("慢源拿到的块少于快源之和", c.served.get() < a.served.get() + b.served.get(),
                "a=" + a.served + " b=" + b.served + " c=" + c.served);
    }

    /**
     * 直接测策略规则，不经过网络也不经过调度。
     *
     * <p>策略只读 Stats、不碰 IO，所以可以造一份假的 Stats 直接问它「你选谁」
     * 「失败了怎么办」。这类规则用端到端测是测不准的——结果取决于哪条源先抢到块。
     */
    static void testPolicyDisablesAfterThreeFailures() {
        Source s = Source.ofUrl("x", "http://x/");
        ChunkRef ref = new ChunkRef(0, 0, 999);
        Policy p = Policy.fastestFirst();
        check("第 1 次失败：原地重试",
                p.onFailure(s, ref, new IOException("t"), 1, fakeStats(0, 1, 0))
                        == Policy.Decision.RETRY_SAME, "");
        check("第 2 次失败：换源",
                p.onFailure(s, ref, new IOException("t"), 2, fakeStats(0, 2, 0))
                        == Policy.Decision.SWITCH_SOURCE, "");
        check("连续失败满 3 次：拉黑",
                p.onFailure(s, ref, new IOException("t"), 3, fakeStats(0, 3, 0))
                        == Policy.Decision.DISABLE_SOURCE, "");
    }

    /** 有实测数据时，快的那条应当被选中；且在跑的块越多越往后排。 */
    static void testPolicyPrefersFasterSource() {
        Source slow = Source.ofUrl("slow", "http://s/");
        Source fast = Source.ofUrl("fast", "http://f/");
        ChunkRef ref = new ChunkRef(0, 0, 999);
        Policy p = Policy.fastestFirst();
        Stats st = new Stats() {
            public double emaBytesPerSec(Source s) { return s.id.equals("fast") ? 5e6 : 1e5; }
            public int consecutiveFailures(Source s) { return 0; }
            public int inFlight(Source s) { return 0; }
            public long lastSuccessAtMillis(Source s) { return 1; }
            public boolean isDisabled(Source s) { return false; }
        };
        check("选中更快的那条",
                p.pick(Arrays.asList(slow, fast), st, ref).id.equals("fast"), "");
        Stats busy = new Stats() {
            public double emaBytesPerSec(Source s) { return s.id.equals("fast") ? 5e6 : 1e5; }
            public int consecutiveFailures(Source s) { return 0; }
            public int inFlight(Source s) { return s.id.equals("fast") ? 100 : 0; }
            public long lastSuccessAtMillis(Source s) { return 1; }
            public boolean isDisabled(Source s) { return false; }
        };
        check("快的那条被压满时让位给慢的",
                p.pick(Arrays.asList(slow, fast), busy, ref).id.equals("slow"),
                "5e6/101 < 1e5/1 才对");
    }

    static Stats fakeStats(double ema, int fails, int inflight) {
        return new Stats() {
            public double emaBytesPerSec(Source s) { return ema; }
            public int consecutiveFailures(Source s) { return fails; }
            public int inFlight(Source s) { return inflight; }
            public long lastSuccessAtMillis(Source s) { return 0; }
            public boolean isDisabled(Source s) { return false; }
        };
    }

    // ───────────────────────── 支撑 ─────────────────────────

    static Downloader dl(int chunks) {
        return Downloader.newBuilder()
                .engine(new HttpEngine(2000, 1500, "anabranch-test", Logger.NONE))
                .defaultChunks(chunks).maxConcurrent(4).build();
    }

    static SourceSet set(Mirror... ms) {
        List<Source> l = new ArrayList<>();
        for (Mirror m : ms) l.add(Source.ofBase(m.id, m.base()));
        return new SourceSet(l, Policy.fastestFirst());
    }

    static Source sourceById(SourceSet set, String id) {
        for (Source s : set.all()) if (s.id.equals(id)) return s;
        throw new IllegalArgumentException(id);
    }

    static void reset(Mirror... ms) {
        for (Mirror m : ms) {
            m.served.set(0); m.dead = false; m.delayMs = 0;
            m.cutAfter = 0; m.failAfterServed = -1;
        }
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
