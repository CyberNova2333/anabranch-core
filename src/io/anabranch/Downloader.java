package io.anabranch;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多源分块下载器。
 *
 * <p>分工：{@link Engine} 只管「取一个字节区间」，{@link Policy} 只管「挑谁、
 * 失败了怎么办」，这里管分块、并发、重试循环、校验与提交。三者都不知道对方的
 * 内部实现——这是能同时支持纯 Java HTTP 与 native aria2、并且能单独测策略的原因。
 */
public final class Downloader {

    /** 分片下限：块太小时每块的请求开销占比会盖过并行收益。 */
    private static final long MIN_UNIT = 256L * 1024;
    /** 单个分片的尝试次数上限。到顶仍失败就让整轮失败——有 .meta 兜底，不丢进度。 */
    private static final int MAX_ATTEMPTS_PER_CHUNK = 6;
    /** 重试之间的退避上限。 */
    private static final long MAX_BACKOFF_MS = 2000;

    private final Engine engine;
    private final int defaultChunks;
    private final int maxConcurrent;
    private final long chunkRetryBudgetMs;
    private final RateLimiter limiter;
    private final Logger log;

    private Downloader(Builder b) {
        this.engine = b.engine;
        this.defaultChunks = b.defaultChunks;
        this.maxConcurrent = b.maxConcurrent;
        this.chunkRetryBudgetMs = b.chunkRetryBudgetMs;
        this.limiter = RateLimiter.perSecond(b.bytesPerSecond);
        this.log = b.log;
    }

    public static Builder newBuilder() { return new Builder(); }

    public static final class Builder {
        private Engine engine = new HttpEngine();
        private int defaultChunks = 8;
        private int maxConcurrent = 4;
        private long chunkRetryBudgetMs = 60_000;
        private long bytesPerSecond = 0;
        private Logger log = Logger.NONE;

        public Builder engine(Engine e) { this.engine = e; return this; }
        public Builder defaultChunks(int n) { this.defaultChunks = Math.max(1, n); return this; }
        public Builder maxConcurrent(int n) { this.maxConcurrent = Math.max(1, n); return this; }

        /**
         * 单个分片**白费**在失败尝试上的时间上限（毫秒），默认 60 秒；<=0 表示不限。
         *
         * <p>为什么需要这个：光有次数上限（{@code MAX_ATTEMPTS_PER_CHUNK}）是不够的。
         * 一条**卡住**的源——不是拒绝，是接了连接却不回数据——每次尝试都要熬满引擎的
         * 读超时，次数上限就把代价乘了上去。默认 30 秒读超时 × 6 次 = 一个分片能卡住
         * 三分钟，而这段时间里它一个字节都没往前走。
         *
         * <p>只算<b>失败</b>尝试与退避的耗时：正在正常传输的尝试不会被这个预算打断，
         * 慢不等于坏。
         */
        public Builder chunkRetryBudgetMs(long ms) { this.chunkRetryBudgetMs = ms; return this; }

        /**
         * 整次下载的限速（字节/秒）；{@code <=0} 表示不限（默认）。
         *
         * <p>限的是**整次下载**，不是每个分片——每个分片各限一份的话，并发数一变
         * 实际速率就跟着变：用户设了 1MB/s，开 4 个分片就成了 4MB/s。
         *
         * <p>实现上限速做在**写入侧**（包装交给 Engine 的那个 channel），因此对任何
         * {@link Engine} 实现都自动生效，也不用把限速的概念塞进 Engine 接口。
         * 代价是引擎可能已经从 socket 读进了缓冲区才被挡住——这层背压不是精确的，
         * 但对「别把带宽吃光」这个目的足够。
         */
        public Builder bytesPerSecond(long n) { this.bytesPerSecond = n; return this; }
        public Builder logger(Logger l) { this.log = l == null ? Logger.NONE : l; return this; }
        public Downloader build() { return new Downloader(this); }
    }

    /**
     * 探测所有源，看它们是不是在说同一件事。
     *
     * <p>值得在正式下载前跑一次：多源最阴的失败模式是某条镜像还是旧版本，
     * 每块都「成功」、合起来是两个版本的拼接。逐块摘要能兜住，但要等整轮下完；
     * 先探一次当场就能看出来。
     */
    public ProbeReport probeAll(String key, SourceSet set, Cancellation c) {
        Map<String, Probe> ok = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (Source s : set.available()) {
            if (c != null && c.isCancelled()) break;
            try {
                ok.put(s.id, engine.probe(s.urlFor(key), c == null ? Cancellation.NONE : c));
            } catch (Exception e) {
                bad.put(s.id, String.valueOf(e.getMessage()));
            }
        }
        return new ProbeReport(ok, bad);
    }

    public Result download(Request r, SourceSet set, Listener l, Cancellation c)
            throws IOException {
        if (l == null) l = Listener.NONE;
        if (c == null) c = Cancellation.NONE;
        if (set == null) throw new IllegalArgumentException("没有 SourceSet");
        set.resetDisabled();

        Probed probed = probeAny(r.key, set, c);
        if (probed.probe.total <= 0) throw new IOException("探测不到总长度：" + r.key);
        l.onTotal(probed.probe.total);

        long unit = chooseUnit(r, probed.probe);
        File part = ResumeState.partFileFor(r.target);
        ResumeState st = r.allowResume && probed.probe.rangeSupported
                ? ResumeState.load(r.target) : null;

        if (st != null && !st.matches(probed.probe.total, probed.probe.etag,
                                      probed.source.id, unit)) {
            log.log("续传状态与服务端不符，丢弃重下：" + r.target.getName());
            st.discard();
            part.delete();
            st = null;
        }
        if (st == null) {
            st = ResumeState.fresh(r.target, probed.probe.total, probed.probe.etag,
                    probed.source.id,
                    probed.probe.rangeSupported ? unit : probed.probe.total);
            part.delete();
        }

        AtomicLong done = new AtomicLong(st.completedBytes());
        l.onProgress(done.get(), probed.probe.total);

        // 服务端不支持 Range 时根本没法续传，留下的 .part/.meta 是纯粹的垃圾：
        // 下一次只能从头再来，而这两个文件还占着与目标文件同样大的空间。
        boolean resumable = r.allowResume && probed.probe.rangeSupported;
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(probed.probe.total);
            FileChannel ch = raf.getChannel();
            long moved = runChunks(r, set, st, ch, probed, l, c, done);
            // 提交前先落盘：先 rename 后 fsync 的话，掉电会留下一个名字正确但内容
            // 不全的文件，而 .meta 已经删了——比没下载还糟。
            ch.force(true);
            commit(r, st, part, probed.probe, l);
            return new Result(probed.probe.total, probed.probe.etag, st.allVerified(), moved);
        } catch (IOException | RuntimeException e) {
            if (!resumable) {
                st.discard();
                part.delete();
            }
            throw e;
        }
    }

    /** 单源便利入口。 */
    public Result download(Request r, String url, Listener l, Cancellation c) throws IOException {
        return download(r, SourceSet.single(url), l, c);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private static final class Probed {
        final Source source; final Probe probe;
        Probed(Source s, Probe p) { source = s; probe = p; }
    }

    /** 依次探，第一条成功的说了算。全挂就把每条的原因都带出来。 */
    private Probed probeAny(String key, SourceSet set, Cancellation c) throws IOException {
        List<Source> avail = set.available();
        if (avail.isEmpty()) throw new IOException("没有可用的源");
        StringBuilder why = new StringBuilder();
        for (Source s : avail) {
            try {
                return new Probed(s, engine.probe(s.urlFor(key), c));
            } catch (Exception e) {
                why.append("\n  ").append(s.id).append(": ").append(e.getMessage());
            }
        }
        throw new IOException("所有源都探测失败：" + why);
    }

    private long chooseUnit(Request r, Probe probe) {
        if (r.integrity != null && r.integrity.hasChunks()) {
            // 有逐块摘要时，分块单位**必须**等于摘要的块大小，否则没法逐块验。
            return r.integrity.chunkSize;
        }
        int n = r.chunks > 0 ? r.chunks : defaultChunks;
        return Math.max(MIN_UNIT, (probe.total + n - 1) / n);
    }

    private long runChunks(Request r, SourceSet set, ResumeState st, FileChannel ch,
                           Probed probed, Listener l, Cancellation c, AtomicLong done)
            throws IOException {
        List<Integer> todo = st.pending();
        if (todo.isEmpty()) return 0;

        AtomicLong transferred = new AtomicLong();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        SpeedMeter meter = new SpeedMeter();
        int threads = Math.min(maxConcurrent, todo.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, run -> {
            Thread t = new Thread(run, "anabranch-chunk");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int idx : todo) {
                final ChunkRef ref = new ChunkRef(idx, st.startOf(idx), st.endOf(idx));
                fs.add(pool.submit(() -> {
                    if (c.isCancelled() || firstError.get() != null) return;
                    try {
                        fetchOneChunk(r, set, st, ch, ref, l, c, meter);
                        long n = ref.length();
                        transferred.addAndGet(n);
                        long soFar = done.addAndGet(n);
                        st.persist();
                        l.onChunkDone(ref.index, ref.start, ref.endInclusive);
                        l.onProgress(soFar, probed.probe.total);
                        l.onSpeed(meter.bytesPerSec());
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    }
                }));
            }
            for (Future<?> f : fs) {
                try { f.get(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("被中断", ie);
                }
                catch (ExecutionException ee) { firstError.compareAndSet(null, ee.getCause()); }
            }
        } finally {
            pool.shutdownNow();
        }
        if (c.isCancelled()) throw new IOException("已取消");
        Throwable err = firstError.get();
        if (err != null) {
            // 失败也要把已完成的块记下来——不然这一轮下好的部分下次全白费。
            try { st.persist(); } catch (IOException ignored) {}
            throw err instanceof IOException ? (IOException) err : new IOException(err);
        }
        return transferred.get();
    }

    /**
     * 取一个分片，带重试与切源。
     *
     * <p>重试循环放在<b>分片粒度</b>而不是整轮粒度：一块失败只影响这一块，
     * 别的线程还在正常跑。第 1 步没有这一层，一块失败就整轮失败——有 .meta 兜底
     * 所以不丢进度，但用户看到的是「下着下着就报错了」。
     */
    private void fetchOneChunk(Request r, SourceSet set, ResumeState st, FileChannel ch,
                               ChunkRef ref, Listener l, Cancellation c, SpeedMeter meter)
            throws IOException {
        Throwable last = null;
        // 白费掉的时间：失败尝试本身 + 退避等待。正在正常传输的尝试不计入，
        // 也不会被打断——慢不等于坏。
        long wastedNs = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_CHUNK; attempt++) {
            if (c.isCancelled()) throw new IOException("已取消");
            if (chunkRetryBudgetMs > 0 && wastedNs / 1_000_000 >= chunkRetryBudgetMs) {
                throw new IOException(ref + " 在失败重试上已白费 " + (wastedNs / 1_000_000)
                        + "ms，超过预算 " + chunkRetryBudgetMs + "ms，放弃这一轮"
                        + "（进度已存 .meta，下次接着下）", last);
            }
            List<Source> avail = set.available();
            if (avail.isEmpty()) {
                throw new IOException("所有源都已被禁用，" + ref + " 无处可取", last);
            }
            Source s = set.policy().pick(avail, set.stats(), ref);
            long t0 = System.nanoTime();
            set.onStart(s);
            try {
                engine.fetchRange(s.urlFor(r.key), ref.start, ref.endInclusive,
                        new PositionedChannel(ch, ref.start, limiter, meter, c), l, c);
                st.mark(ref.index, ResumeState.DONE);

                if (r.integrity != null && r.integrity.hasChunks()) {
                    String want = r.integrity.hashFor(ref.index);
                    if (want != null) {
                        String got = sha256Of(ch, ref.start, ref.length());
                        if (!want.equalsIgnoreCase(got)) {
                            // 退回 PENDING：这一块要重下。标成 DONE 会让坏数据一路混到
                            // 最后的整份校验，那时已经分不清是哪一块坏的了。
                            st.mark(ref.index, ResumeState.PENDING);
                            l.onChunkVerifyFailed(ref.index, want, got);
                            throw new IOException("第 " + ref.index + " 块校验不通过（源 "
                                    + s.id + "）");
                        }
                        st.mark(ref.index, ResumeState.VERIFIED);
                    }
                }
                set.onSuccess(s, ref.length(), System.nanoTime() - t0);
                return;
            } catch (Throwable e) {
                set.onFailure(s);
                last = e;
                wastedNs += System.nanoTime() - t0;
                if (c.isCancelled()) throw new IOException("已取消");
                Policy.Decision d = set.policy().onFailure(s, ref, e, attempt, set.stats());
                log.log("[" + ref + "] 源 " + s.id + " 第 " + attempt + " 次失败 → " + d, e);
                l.onSourceSwitch(s, null, ref, e);
                if (d == Policy.Decision.ABORT) break;
                if (d == Policy.Decision.DISABLE_SOURCE) set.disable(s);
                // RETRY_SAME / SWITCH_SOURCE 都是继续循环——具体挑谁交给下一轮 pick，
                // 因为「同一条源」在被禁用之后可能已经不可选了。
                wastedNs += backoff(attempt, c);
            }
        }
        throw last instanceof IOException ? (IOException) last
                : new IOException(ref + " 取不回来", last);
    }

    /**
     * 重试之间退避，返回实际等了多少纳秒。
     *
     * <p>立刻重试等于对着一台已经出问题的服务器猛敲：它多半正忙着或正在重启，
     * 而你的重试让它更忙。指数退避封顶 {@value #MAX_BACKOFF_MS}ms——再长就该换源了，
     * 而换源本来就是策略在管的事。
     *
     * <p>等待期间响应取消：否则「点了取消却还要再等两秒」在界面上就是卡住。
     */
    private long backoff(int attempt, Cancellation c) {
        long ms = Math.min(MAX_BACKOFF_MS, 200L << Math.min(attempt - 1, 16));
        long t0 = System.nanoTime();
        long deadline = t0 + ms * 1_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                if (c.isCancelled()) break;
                Thread.sleep(Math.min(50, ms));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.nanoTime() - t0;
    }

    private void commit(Request r, ResumeState st, File part, Probe probe, Listener l)
            throws IOException {
        if (!st.isComplete()) throw new IOException("仍有未完成的块，不提交");

        if (r.integrity != null && r.integrity.wholeSha256 != null) {
            try (RandomAccessFile raf = new RandomAccessFile(part, "r")) {
                String got = sha256Of(raf.getChannel(), 0, probe.total);
                if (!r.integrity.wholeSha256.equalsIgnoreCase(got)) {
                    throw new IOException("整份校验不通过：期望 "
                            + r.integrity.wholeSha256 + " 实得 " + got);
                }
            }
        }
        File target = r.target;
        target.delete();
        if (!part.renameTo(target)) throw new IOException("提交失败：" + part + " → " + target);
        ResumeState.syncDir(target.getParentFile());
        st.discard();
        l.onProgress(probe.total, probe.total);
    }

    private static String sha256Of(FileChannel ch, long pos, long len) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long left = len, p = pos;
            while (left > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), left));
                int n = ch.read(buf, p);
                if (n <= 0) throw new IOException("校验读取意外结束，剩 " + left);
                buf.flip();
                md.update(buf);
                p += n; left -= n;
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                          .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("没有 SHA-256", e);   // 不该发生
        }
    }

    /**
     * 把「顺序写」翻译成「从 base 起的定位写」。
     *
     * <p>{@link FileChannel#write(ByteBuffer, long)} 不动通道自身的 position，
     * 所以多个线程可以共用一个 FileChannel 各写各的区间——不用每个 worker 开一个
     * 文件句柄，也不用加锁。
     */
    static final class PositionedChannel implements WritableByteChannel {
        private final FileChannel ch;
        private final RateLimiter limiter;
        private final SpeedMeter meter;
        private final Cancellation cancel;
        private long pos;
        private volatile boolean open = true;

        PositionedChannel(FileChannel ch, long base, RateLimiter limiter,
                          SpeedMeter meter, Cancellation cancel) {
            this.ch = ch;
            this.pos = base;
            this.limiter = limiter == null ? RateLimiter.UNLIMITED : limiter;
            this.meter = meter;
            this.cancel = cancel == null ? Cancellation.NONE : cancel;
        }

        @Override public int write(ByteBuffer src) throws IOException {
            int want = src.remaining();
            if (want <= 0) return 0;
            if (limiter.isLimited()) {
                // 只写限速器放行的那么多，剩下的交给引擎的下一次 write。
                // 一次性等够整个缓冲区的额度会让写入一顿一顿的。
                int allow = limiter.acquire(want, cancel);
                if (allow <= 0) throw new IOException("已取消");
                if (allow < want) {
                    int keep = src.limit();
                    src.limit(src.position() + allow);
                    int n = doWrite(src);
                    src.limit(keep);
                    return n;
                }
            }
            return doWrite(src);
        }

        private int doWrite(ByteBuffer src) throws IOException {
            int n = ch.write(src, pos);
            pos += n;
            if (meter != null) meter.record(n);
            return n;
        }

        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }   // 不关共享通道
    }
}
