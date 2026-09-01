package io.anabranch.aria2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import io.anabranch.Cancellation;
import io.anabranch.Listener;
import io.anabranch.Logger;
import io.anabranch.Request;
import io.anabranch.Result;
import io.anabranch.Source;
import io.anabranch.SourceSet;

/**
 * aria2 后端：整文件、多 URI，分段与切源交给 aria2 自己。
 *
 * <p>对外与 core 的 {@code Downloader} 同一公共面，使用者换后端不改调用代码。
 * 两条语义差异必须知道（详见 docs/ARIA2.md §二）：
 *
 * <ul>
 * <li>{@link SourceSet} 里的 {@code Policy}/{@code Stats} <b>不参与</b>——aria2
 *     有自己的选路与重试；{@code Source.weight} 映射不了，忽略并记日志。</li>
 * <li>逐块/整份校验仍然是我们的：aria2 下完后、提交前按 {@code Integrity}
 *     自验，校验不过不提交——与 core 同一条铁律。刻意<b>不用</b> aria2 的
 *     checksum 选项：校验只有一个出处，行为才可预期。</li>
 * </ul>
 *
 * <p>续传交给 aria2 自己的 {@code .aria2} 控制文件（core 的 {@code .meta}
 * 不出现在这条路上）；临时产物是 {@code <target>.a2part}，提交时 rename。
 *
 * <p>线程模型：{@code download()} 全程在调用线程上驱动 aria2 的事件循环
 * （{@code runOnce} 会阻塞到有事件或内部超时，不空转）；libaria2 不是线程
 * 安全的，所以本类把 download 串行化。取消经 {@link Cancellation} 轮询，
 * 时延约一个事件循环周期（秒级以内）。
 *
 * <p>后端加载一次定终身：两个 TLS 后端导出同名 JNI 符号，同一进程装两份
 * 会让符号解析变成掷骰子——所以进程内第一次加载成功的后端一直用到进程
 * 结束，切换只在下次启动生效（dead-man's switch 的语义本来就是这样）。
 */
public final class Aria2Downloader {

    /** 进程内已加载的后端；null = 还没加载。见类注释「一次定终身」。 */
    private static String loadedBackend;

    /** libaria2 同时只允许一个 session，且必须单线程驱动——全局串行化。 */
    private static final Object BUSY = new Object();

    /** 进度/速度回调的节流间隔：aria2 的统计本身秒级更新，更密只是噪声。 */
    private static final long CALLBACK_INTERVAL_MS = 200;

    /** 关停后最多再等这么久让事件循环停干净；超过就放弃、保留 armed 标记。 */
    private static final long DRAIN_BUDGET_MS = 10_000;

    private final BackendSelector selector;
    private final Logger log;

    private Aria2Downloader(Builder b) {
        this.selector = b.selector;
        this.log = b.log;
    }

    public static Builder newBuilder() { return new Builder(); }

    public static final class Builder {
        private File stateDir;
        private BackendSelector selector;
        private Logger log = Logger.NONE;

        /** 状态目录：armed 标记等落在这里。必填。 */
        public Builder stateDir(File dir) { this.stateDir = dir; return this; }

        /** 默认用 {@link FileBackendSelector}（openssl ↔ gnutls）；测试可注入假的。 */
        public Builder selector(BackendSelector s) { this.selector = s; return this; }

        public Builder logger(Logger l) { this.log = l == null ? Logger.NONE : l; return this; }

        public Aria2Downloader build() {
            if (stateDir == null) throw new IllegalStateException("stateDir 必填");
            if (selector == null) {
                selector = new FileBackendSelector(stateDir, "aria2-openssl", "aria2-gnutls");
            }
            return new Aria2Downloader(this);
        }
    }

    public Result download(Request r, SourceSet set, Listener l, Cancellation c)
            throws IOException {
        if (l == null) l = Listener.NONE;
        if (c == null) c = Cancellation.NONE;
        if (set == null) throw new IllegalArgumentException("没有 SourceSet");
        synchronized (BUSY) {
            return doDownload(r, set, l, c);
        }
    }

    // ── 主流程 ──────────────────────────────────────────────────────

    private Result doDownload(Request r, SourceSet set, Listener l, Cancellation c)
            throws IOException {
        List<Source> avail = set.available();
        if (avail.isEmpty()) throw new IOException("没有可用的源");
        List<String> uris = new ArrayList<>(avail.size());
        for (Source s : avail) uris.add(s.urlFor(r.key));
        log.log("aria2 后端：" + uris.size() + " 条源交给 aria2 调度（Policy/weight 不参与）");

        File target = r.target;
        File dir = target.getParentFile() == null ? new File(".") : target.getParentFile();
        dir.mkdirs();
        File tmp = new File(dir, target.getName() + ".a2part");
        File ctrl = new File(dir, tmp.getName() + ".aria2");
        if (!r.allowResume) {
            // 不续传就别让 aria2 读到上一次的残留。
            tmp.delete();
            ctrl.delete();
        }

        armAndLoad();

        long session = Aria2Native.sessionNew(new String[] {});
        if (session == 0) {
            selector.disarm();
            throw new IOException("aria2 session 建立失败");
        }

        boolean stoppedClean = false;
        try {
            List<String> opt = new ArrayList<>();
            opt.add("dir");      opt.add(dir.getAbsolutePath());
            opt.add("out");      opt.add(tmp.getName());
            opt.add("continue"); opt.add(r.allowResume ? "true" : "false");
            // 预分配交给文件系统：fallocate 在个别文件系统上会整段卡住，
            // 而这个库的 .part 本来就允许稀疏。
            opt.add("file-allocation"); opt.add("none");
            if (r.chunks > 0) { opt.add("split"); opt.add(String.valueOf(r.chunks)); }

            String gid = Aria2Native.addUri(session,
                    uris.toArray(new String[0]), opt.toArray(new String[0]));
            if (gid == null) throw new IOException("aria2 addUri 失败（URI 或选项不被接受）");

            long total = runUntilDone(session, gid, l, c);

            boolean chunkVerified = verify(r, tmp, total, l);
            commit(target, tmp, ctrl, dir);
            l.onProgress(total, total);
            return new Result(total, null, chunkVerified, total);
        } finally {
            stoppedClean = stopSession(session);
            // 只有确认 native 侧干净停下才 disarm——这是 dead-man's switch 的
            // 全部意义所在。停不下来就保留 armed：宁可下次启动多换一次后端，
            // 也不要把一个行为异常的后端标成「干净退出过」。
            if (stoppedClean) selector.disarm();
            else log.log("aria2 session 未能在预算内停净，保留 armed 标记");
        }
    }

    /**
     * 驱动事件循环直到下载完成；出错/取消/意外停止都以异常离开。
     * 返回文件总长。
     */
    private long runUntilDone(long session, String gid, Listener l, Cancellation c)
            throws IOException {
        long total = -1;
        long lastCb = 0;
        boolean cancelRequested = false;
        while (true) {
            if (!cancelRequested && c.isCancelled()) {
                cancelRequested = true;
                // 同一线程、两次 runOnce 之间——这正是允许调 shutdown 的时机。
                Aria2Native.sessionShutdown(session);
            }
            int rc = Aria2Native.runOnce(session);
            if (rc < 0) throw new IOException("aria2 事件循环出错");

            long[] p = Aria2Native.progressOf(session, gid);
            if (p != null) {
                if (total <= 0 && p[1] > 0) {
                    total = p[1];
                    l.onTotal(total);
                }
                long now = System.currentTimeMillis();
                if (now - lastCb >= CALLBACK_INTERVAL_MS) {
                    lastCb = now;
                    if (p[1] > 0) l.onProgress(p[0], p[1]);
                    l.onSpeed(p[2]);
                }
            }

            if (cancelRequested) {
                if (rc == 0) throw new IOException("已取消");
                continue;   // shutdown 已发出，把循环跑到停为止
            }

            int st = Aria2Native.statusOf(session, gid);
            if (st == Aria2Native.STATUS_COMPLETE) {
                if (total <= 0) {
                    long[] fin = Aria2Native.progressOf(session, gid);
                    total = fin != null ? fin[1] : -1;
                }
                if (total <= 0) throw new IOException("aria2 报告完成但拿不到总长");
                return total;
            }
            if (st == Aria2Native.STATUS_ERROR) {
                String why = Aria2Native.errorOf(session, gid);
                // 网络类失败保留 .a2part/.aria2——下一次 allowResume 还能接着下。
                throw new IOException("aria2 下载失败"
                        + (why == null ? "" : "（" + why + "）")
                        + "，码表见 aria2 的 EXIT STATUS 文档");
            }
            if (rc == 0) {
                // keepRunning=true 下事件循环不该自己停；停了就是 native 侧异常。
                throw new IOException("aria2 会话意外停止（状态 " + st + "）");
            }
        }
    }

    /** 校验：逐块（有 chunkHashes 时）+ 整份（有 wholeSha256 时）。不过就删产物并抛。 */
    private boolean verify(Request r, File tmp, long total, Listener l) throws IOException {
        if (r.integrity == null) return false;
        boolean chunkVerified = false;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "r")) {
            if (raf.length() != total) {
                // aria2 说完成、长度却不对——内容不可信，走删干净那条路。
                throw new VerifyException("产物长度不符：期望 " + total + " 实得 " + raf.length());
            }
            if (r.integrity.hasChunks()) {
                long unit = r.integrity.chunkSize;
                int n = (int) ((total + unit - 1) / unit);
                for (int i = 0; i < n; i++) {
                    String want = r.integrity.hashFor(i);
                    if (want == null) continue;
                    long start = i * unit;
                    long len = Math.min(unit, total - start);
                    String got = sha256Of(raf, start, len);
                    if (!want.equalsIgnoreCase(got)) {
                        l.onChunkVerifyFailed(i, want, got);
                        throw new VerifyException("第 " + i + " 块校验不通过");
                    }
                }
                chunkVerified = true;
            }
            if (r.integrity.wholeSha256 != null) {
                String got = sha256Of(raf, 0, total);
                if (!r.integrity.wholeSha256.equalsIgnoreCase(got)) {
                    throw new VerifyException("整份校验不通过：期望 "
                            + r.integrity.wholeSha256 + " 实得 " + got);
                }
            }
        } catch (VerifyException e) {
            // 校验不过 = 内容是错的，留着只会被下次「续传」接着用——删干净。
            tmp.delete();
            new File(tmp.getParent(), tmp.getName() + ".aria2").delete();
            throw new IOException(e.getMessage());
        }
        return chunkVerified;
    }

    private static final class VerifyException extends IOException {
        VerifyException(String msg) { super(msg); }
    }

    private void commit(File target, File tmp, File ctrl, File dir) throws IOException {
        target.delete();
        if (!tmp.renameTo(target)) throw new IOException("提交失败：" + tmp + " → " + target);
        ctrl.delete();
        syncDir(dir);
    }

    // ── 后端加载与 session 收尾 ────────────────────────────────────

    /**
     * 选后端、arm、加载。加载期失败（可捕获）当场换另一个再试一次；
     * 运行期崩溃没有 catch 的机会，靠 armed 标记留给下次启动判断。
     */
    private void armAndLoad() throws IOException {
        synchronized (Aria2Downloader.class) {
            if (loadedBackend != null) {
                selector.arm(loadedBackend);
                return;
            }
            String be = selector.current();
            selector.arm(be);
            try {
                Aria2Native.load(be);
                loadedBackend = be;
            } catch (UnsatisfiedLinkError e) {
                // 顺序有讲究：先 disarm（这不是死于非命，别让下次启动误判），
                // 再 reportLoadFailure 把偏好切过去。
                selector.disarm();
                selector.reportLoadFailure(be);
                String other = selector.current();
                log.log("后端 " + be + " 加载失败，当场换 " + other, e);
                selector.arm(other);
                try {
                    Aria2Native.load(other);
                    loadedBackend = other;
                } catch (UnsatisfiedLinkError e2) {
                    selector.disarm();
                    throw new IOException("两个 aria2 后端都加载失败：" + be + " / " + other, e2);
                }
            }
        }
    }

    /** shutdown → 把事件循环跑到停（有预算）→ sessionFinal。返回是否停净。 */
    private boolean stopSession(long session) {
        Aria2Native.sessionShutdown(session);
        long deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Aria2Native.runOnce(session) <= 0) {
                Aria2Native.sessionRelease(session);
                return true;
            }
        }
        // 停不下来就不 sessionFinal——文档要求 run 返回 0 之后才能 final，
        // 硬来是拿崩溃换整洁。session 泄漏到进程结束，armed 标记留给下次启动。
        return false;
    }

    private static String sha256Of(RandomAccessFile raf, long pos, long len) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long left = len;
            raf.seek(pos);
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) throw new IOException("校验读取意外结束，剩 " + left);
                md.update(buf, 0, n);
                left -= n;
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                          .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("没有 SHA-256", e);   // 不该发生
        }
    }

    /** 目录 fsync 的土办法（Java 没有直接 API）：与 core 的做法同源。 */
    private static void syncDir(File dir) {
        try {
            File t = File.createTempFile(".anabranch-sync", null, dir);
            try (FileOutputStream fos = new FileOutputStream(t, true)) {
                fos.getFD().sync();
            }
            t.delete();
        } catch (IOException ignored) {
            // 同步目录失败不致命：最坏情况是掉电后 rename 丢失，文件重下。
        }
    }
}
