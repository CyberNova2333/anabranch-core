import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.anabranch.*;
import io.anabranch.aria2.Aria2Downloader;
import io.anabranch.aria2.BackendSelector;
import io.anabranch.aria2.FileBackendSelector;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aria2Downloader 的端到端测试：载入**真的** libaria2（宿主构建，见
 * aria2/native/build-host.sh），对着真实本地 HTTP 服务器传真字节。
 * 跑法：sh aria2/native/run-host-tests.sh（它负责先把宿主 .so 编出来）。
 *
 * <p>顺序敏感：后端「一次加载定终身」（两个后端导出同名 JNI 符号，进程内
 * 只能装一份），所以「加载失败换后端」必须是本进程第一个用例——一旦
 * aria2-openssl 装上了，后面永远走不到加载失败的分支。
 */
public class Aria2DownloaderTest {

    static int passed = 0, failed = 0;
    static byte[] BODY;

    /** 一台镜像：支持 Range、可切「挂」、可限流、可「发一截就再也不理」。 */
    static final class Mirror {
        final HttpServer srv;
        final ExecutorService pool;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger rangeRequests = new AtomicInteger();
        final AtomicLong bytesSent = new AtomicLong();
        final List<String> requestLog = new ArrayList<>();
        volatile boolean dead = false;
        /** >0：发这么多字节后掐断连接，并把自己切成 dead（模拟下到一半掉线）。 */
        volatile int cutAfterThenDie = 0;
        /** >0：每写 8KB 睡这么多毫秒（把下载拖慢，给取消留窗口）。 */
        volatile int throttleMs = 0;

        Mirror() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "a2-mirror"); t.setDaemon(true); return t; });
            srv.createContext("/", this::serve);
            srv.setExecutor(pool);
            srv.start();
        }

        String base() { return "http://127.0.0.1:" + srv.getAddress().getPort() + "/"; }
        void stop() { srv.stop(0); pool.shutdownNow(); }

        void reset() {
            requests.set(0); rangeRequests.set(0); bytesSent.set(0);
            dead = false; cutAfterThenDie = 0; throttleMs = 0;
            synchronized (requestLog) { requestLog.clear(); }
        }

        String requestLogText() {
            synchronized (requestLog) { return String.join("; ", requestLog); }
        }

        void serve(HttpExchange x) throws IOException {
            requests.incrementAndGet();
            synchronized (requestLog) {
                requestLog.add(x.getRequestMethod() + " Range="
                        + x.getRequestHeaders().getFirst("Range") + (dead ? " [dead]" : ""));
            }
            if (dead) { x.sendResponseHeaders(503, -1); x.close(); return; }
            int from = 0, to = BODY.length - 1;
            String range = x.getRequestHeaders().getFirst("Range");
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                rangeRequests.incrementAndGet();
                String[] p = range.substring(6).split("-");
                from = Integer.parseInt(p[0]);
                to = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) : BODY.length - 1;
                partial = true;
            }
            x.getResponseHeaders().add("Accept-Ranges", "bytes");
            int len = to - from + 1;
            if (partial) {
                x.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + to + "/" + BODY.length);
                x.sendResponseHeaders(206, len);
            } else {
                x.sendResponseHeaders(200, len);
            }
            int cut = cutAfterThenDie;
            int limit = cut > 0 ? Math.min(len, cut) : len;
            try (OutputStream os = x.getResponseBody()) {
                int off = from, left = limit;
                while (left > 0) {
                    int n = Math.min(8192, left);
                    os.write(BODY, off, n);
                    bytesSent.addAndGet(n);
                    off += n; left -= n;
                    if (throttleMs > 0) {
                        try { Thread.sleep(throttleMs); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (IOException ignored) {
                // 客户端取消时写端收 broken pipe，属预期
            }
            if (cut > 0 && limit < len) dead = true;   // 发了一截 → 从此装死
            x.close();
        }
    }

    public static void main(String[] args) throws Exception {
        BODY = new byte[400_000];
        new Random(11).nextBytes(BODY);
        Mirror a = new Mirror();
        Mirror b = new Mirror();
        File dir = Files.createTempDirectory("ab-a2dl").toFile();
        try {
            // 必须第一个跑，理由见类注释。
            testLoadFailureFailsOver(a, dir);
            testSingleSourceDownload(a, dir);
            testDeadMirrorRoutedAround(a, b, dir);
            testCallbacksFed(a, dir);
            testWholeHashMismatchRejected(a, dir);
            testChunkHashes(a, dir);
            testCancelStopsEarly(a, dir);
            testResumeAfterFailure(a, dir);
        } finally {
            a.stop(); b.stop();
        }
        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ───────────────────────── 用例 ─────────────────────────

    /** 首选后端不存在 → 当场换另一个并下载成功；偏好被切换粘住。 */
    static void testLoadFailureFailsOver(Mirror a, File dir) throws Exception {
        a.reset();
        File state = new File(dir, "state-failover");
        // A 是不存在的库名（System.loadLibrary 必失败），B 是宿主真库。
        BackendSelector sel = new FileBackendSelector(state, "aria2-noexist", "aria2-openssl");
        File out = new File(dir, "failover.bin");
        Aria2Downloader dl = Aria2Downloader.newBuilder()
                .stateDir(state).selector(sel).build();
        Result r = dl.download(Request.of("f", out), set(a), Listener.NONE, Cancellation.NONE);
        check("加载失败当场换后端并下载成功", same(out, BODY), "");
        check("总长正确", r.totalBytes == BODY.length, "" + r.totalBytes);
        check("切换后偏好粘住", new FileBackendSelector(state, "aria2-noexist", "aria2-openssl")
                .current().equals("aria2-openssl"), "");
    }

    /** 单源：内容一致、临时产物清理干净、armed 已 disarm。 */
    static void testSingleSourceDownload(Mirror a, File dir) throws Exception {
        a.reset();
        File state = new File(dir, "state-single");
        File out = new File(dir, "single.bin");
        dl(state).download(Request.of("f", out), set(a), Listener.NONE, Cancellation.NONE);
        check("单源下载内容一致", same(out, BODY), "");
        check("临时 .a2part 清理干净", !new File(dir, "single.bin.a2part").exists(), "");
        check(".aria2 控制文件清理干净", !new File(dir, "single.bin.a2part.aria2").exists(), "");
        check("干净关停后 armed 标记不在", !new File(state, "aria2.armed").exists(), "");
    }

    /** 多 URI：一台全挂，aria2 自己换到好的那台。 */
    static void testDeadMirrorRoutedAround(Mirror a, Mirror b, File dir) throws Exception {
        a.reset(); b.reset();
        a.dead = true;
        File out = new File(dir, "dead.bin");
        dl(new File(dir, "state-dead")).download(Request.of("f", out), set(a, b),
                Listener.NONE, Cancellation.NONE);
        check("一台全挂仍能下完", same(out, BODY), "");
        check("字节确实来自活着的那台", b.bytesSent.get() >= BODY.length,
                "b 发了 " + b.bytesSent + " 字节");
        a.dead = false;
    }

    /** onTotal/onProgress/onSpeed 都被喂过，且值合理。 */
    static void testCallbacksFed(Mirror a, File dir) throws Exception {
        a.reset();
        a.throttleMs = 20;   // 拖长一点，保证进度回调有机会按节流间隔发出
        File out = new File(dir, "cb.bin");
        long[] totalSeen = {-1};
        List<Long> progress = new ArrayList<>();
        boolean[] speedSeen = {false};
        dl(new File(dir, "state-cb")).download(Request.of("f", out), set(a),
                new Listener() {
                    public void onTotal(long total) { totalSeen[0] = total; }
                    public void onProgress(long soFar, long total) {
                        synchronized (progress) { progress.add(soFar); }
                    }
                    public void onSpeed(double bps) { if (bps >= 0) speedSeen[0] = true; }
                }, Cancellation.NONE);
        a.throttleMs = 0;
        check("onTotal 报了正确总长", totalSeen[0] == BODY.length, "" + totalSeen[0]);
        boolean monotonic = true;
        for (int i = 1; i < progress.size(); i++) {
            if (progress.get(i) < progress.get(i - 1)) { monotonic = false; break; }
        }
        check("onProgress 有喂且单调不减", !progress.isEmpty() && monotonic,
                "共 " + progress.size() + " 次");
        check("onSpeed 有喂且非负", speedSeen[0], "");
    }

    /** 整份 sha256 不对 → 抛错、不产出目标、坏产物不留。 */
    static void testWholeHashMismatchRejected(Mirror a, File dir) throws Exception {
        a.reset();
        File out = new File(dir, "badwhole.bin");
        String msg = "";
        try {
            dl(new File(dir, "state-badwhole")).download(
                    new Request("f", out, Integrity.wholeOnly(repeat('0', 64)), 0, true),
                    set(a), Listener.NONE, Cancellation.NONE);
        } catch (IOException e) { msg = String.valueOf(e.getMessage()); }
        check("整份校验不过会抛错", msg.contains("整份校验不通过"), msg);
        check("坏内容没被提交成目标", !out.exists(), "");
        check("坏产物 .a2part 已删（防被下次续传接着用）",
                !new File(dir, "badwhole.bin.a2part").exists(), "");
    }

    /** 逐块摘要：全对 → chunkVerified；错一块 → 报对块号并拒绝。 */
    static void testChunkHashes(Mirror a, File dir) throws Exception {
        a.reset();
        long unit = 128 * 1024;
        int n = (int) ((BODY.length + unit - 1) / unit);
        String[] hashes = new String[n];
        for (int i = 0; i < n; i++) {
            int start = (int) (i * unit);
            int len = (int) Math.min(unit, BODY.length - start);
            hashes[i] = sha256(BODY, start, len);
        }
        File out = new File(dir, "chunks.bin");
        Result r = dl(new File(dir, "state-chunks")).download(
                new Request("f", out, new Integrity(unit, hashes, null), 0, true),
                set(a), Listener.NONE, Cancellation.NONE);
        check("逐块全对时下载成功", same(out, BODY), "");
        check("Result 报告逐块校验过", r.chunkVerified, "");

        String[] bad = hashes.clone();
        bad[1] = repeat('f', 64);
        File out2 = new File(dir, "chunks-bad.bin");
        int[] failedIndex = {-1};
        String msg = "";
        try {
            dl(new File(dir, "state-chunks2")).download(
                    new Request("f", out2, new Integrity(unit, bad, null), 0, true),
                    set(a),
                    new Listener() {
                        public void onChunkVerifyFailed(int idx, String want, String got) {
                            failedIndex[0] = idx;
                        }
                    }, Cancellation.NONE);
        } catch (IOException e) { msg = String.valueOf(e.getMessage()); }
        check("错块被拦下", msg.contains("第 1 块"), msg);
        check("onChunkVerifyFailed 报了正确的块号", failedIndex[0] == 1, "" + failedIndex[0]);
        check("错块时不产出目标", !out2.exists(), "");
    }

    /** 取消：中途停下、不产出目标；判据是服务端没把整份发完，不赌墙上时间。 */
    static void testCancelStopsEarly(Mirror a, File dir) throws Exception {
        a.reset();
        a.throttleMs = 60;   // 8KB/60ms ≈ 133KB/s，400KB 全量要约 3 秒
        File out = new File(dir, "cancel.bin");
        long[] firstProgressAt = {0};
        Cancellation afterFirstProgress = () -> firstProgressAt[0] != 0;
        String msg = "";
        try {
            dl(new File(dir, "state-cancel")).download(Request.of("f", out), set(a),
                    new Listener() {
                        public void onProgress(long soFar, long total) {
                            if (soFar > 0 && firstProgressAt[0] == 0) {
                                firstProgressAt[0] = System.nanoTime();
                            }
                        }
                    }, afterFirstProgress);
        } catch (IOException e) { msg = String.valueOf(e.getMessage()); }
        a.throttleMs = 0;
        check("取消会抛错而不是悄悄成功", msg.contains("已取消"), msg);
        check("取消后没有目标文件", !out.exists(), "");
        check("服务端没把整份发完（取消真的停了传输）",
                a.bytesSent.get() < BODY.length,
                "发了 " + a.bytesSent + " / " + BODY.length);
    }

    /** 断点续传：第一轮下到一半失败，第二轮从断点接着下（aria2 自己的 .aria2）。 */
    static void testResumeAfterFailure(Mirror a, File dir) throws Exception {
        a.reset();
        a.cutAfterThenDie = 150_000;   // 发 150KB 就掐线并装死 → 第一轮必失败
        File state = new File(dir, "state-resume");
        File out = new File(dir, "resume.bin");
        String msg = "";
        try {
            dl(state).download(Request.of("f", out), set(a), Listener.NONE, Cancellation.NONE);
        } catch (IOException e) { msg = String.valueOf(e.getMessage()); }
        check("掐线后第一轮失败", !msg.isEmpty(), "");
        check("失败后留下续传现场（.a2part + .aria2）",
                new File(dir, "resume.bin.a2part").exists()
                        && new File(dir, "resume.bin.a2part.aria2").exists(), "");

        a.reset();                      // 服务端复活
        dl(state).download(Request.of("f", out), set(a), Listener.NONE, Cancellation.NONE);
        check("第二轮续完且内容一致", same(out, BODY), "");
        // 判据是请求序列，不是服务端计数：aria2 续传前会先开一条无 Range 的
        // 探测连接、确认后掐掉再发真正的 Range 请求，而被掐的连接在服务端看
        // 还会往 socket 缓冲区里灌一阵——拿 bytesSent 断言等于赌缓冲行为
        // （第一版就是这么写的，误报了）。真正说明「不是整份重下」的是：
        // 存在一个从非零偏移开始的 Range 请求。
        long resumeOffset = -1;
        List<String> lines;
        synchronized (a.requestLog) { lines = new ArrayList<>(a.requestLog); }
        for (String line : lines) {
            int i = line.indexOf("Range=bytes=");
            if (i >= 0) {
                String tail = line.substring(i + "Range=bytes=".length());
                long off = Long.parseLong(tail.substring(0, tail.indexOf('-')));
                resumeOffset = Math.max(resumeOffset, off);
            }
        }
        check("确实从中间续传而不是整份重下", resumeOffset > 0,
                "请求序列：" + a.requestLogText());
        check("续传起点不超过第一轮已下的量", resumeOffset <= 150_000,
                "resumeOffset=" + resumeOffset);
    }

    // ───────────────────────── 支撑 ─────────────────────────

    static Aria2Downloader dl(File stateDir) {
        return Aria2Downloader.newBuilder()
                .stateDir(stateDir)
                .selector(new FileBackendSelector(stateDir, "aria2-openssl", "aria2-gnutls"))
                .build();
    }

    static SourceSet set(Mirror... ms) {
        List<Source> l = new ArrayList<>();
        int i = 0;
        for (Mirror m : ms) l.add(Source.ofBase("m" + (i++), m.base()));
        return new SourceSet(l, null);
    }

    static boolean same(File f, byte[] want) throws IOException {
        if (!f.isFile() || f.length() != want.length) return false;
        byte[] got = new byte[(int) f.length()];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.readFully(got); }
        return Arrays.equals(got, want);
    }

    static String sha256(byte[] data, int off, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data, off, len);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                      .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else { failed++; System.out.println("  ✘ " + name + (detail.isEmpty() ? "" : "  —— " + detail)); }
    }
}
