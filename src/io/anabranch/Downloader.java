package io.anabranch;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单源分块下载器（第 1 步：不含多源）。
 *
 * <p>先做单源是刻意的：多源是这个库的卖点，但它建立在「单源分块是对的」之上。
 * 反过来先做多源，一旦拼出坏文件，分不清是调度错了还是分块错了。
 *
 * <p>多源要接进来的位置只有一处——{@link #urlFor}。到时候把它换成
 * {@code SourceSet.pick(...)} 返回的那条源的 URL，其余（分块、续传、校验、
 * 提交）一行不用动。这就是把边界画在这里的理由。
 */
public final class Downloader {

    /** 分片下限：块太小时每块的请求开销占比会盖过并行收益。 */
    private static final long MIN_UNIT = 256L * 1024;

    private final Engine engine;
    private final int defaultChunks;
    private final int maxConcurrent;
    private final Logger log;

    private Downloader(Builder b) {
        this.engine = b.engine;
        this.defaultChunks = b.defaultChunks;
        this.maxConcurrent = b.maxConcurrent;
        this.log = b.log;
    }

    public static Builder newBuilder() { return new Builder(); }

    public static final class Builder {
        private Engine engine = new HttpEngine();
        private int defaultChunks = 8;
        private int maxConcurrent = 4;
        private Logger log = Logger.NONE;

        public Builder engine(Engine e) { this.engine = e; return this; }
        public Builder defaultChunks(int n) { this.defaultChunks = Math.max(1, n); return this; }
        public Builder maxConcurrent(int n) { this.maxConcurrent = Math.max(1, n); return this; }
        public Builder logger(Logger l) { this.log = l == null ? Logger.NONE : l; return this; }
        public Downloader build() { return new Downloader(this); }
    }

    /** 多源接进来的唯一位置。现在是恒等函数。 */
    private String urlFor(Request r, int chunkIndex) { return r.url; }

    public Result download(Request r, Listener l, Cancellation c)
            throws IOException {
        if (l == null) l = Listener.NONE;
        if (c == null) c = Cancellation.NONE;

        Probe probe = engine.probe(r.url, c);
        if (probe.total <= 0) throw new IOException("探测不到总长度：" + r.url);
        l.onTotal(probe.total);

        long unit = chooseUnit(r, probe);
        File part = ResumeState.partFileFor(r.target);
        ResumeState st = r.allowResume && probe.rangeSupported ? ResumeState.load(r.target) : null;

        if (st != null && !st.matches(probe.total, probe.etag, unit)) {
            // 长度/etag/分块单位任一对不上，旧状态就没有意义。连 .part 一起丢——
            // 留着它只会让下一次又走到这里重判一遍。
            log.log("续传状态与服务端不符，丢弃重下：" + r.target.getName());
            st.discard();
            part.delete();
            st = null;
        }
        if (st == null) {
            st = ResumeState.fresh(r.target, probe.total,
                    probe.etag, probe.rangeSupported ? unit : probe.total);
            part.delete();
        }

        final AtomicLong done = new AtomicLong(st.completedBytes());
        l.onProgress(done.get(), probe.total);

        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(probe.total);
            FileChannel ch = raf.getChannel();
            long transferred = runChunks(r, st, ch, probe, l, c, done);
            // 提交之前先把数据落盘：先 rename 后 fsync 的话，掉电会留下一个
            // 名字正确但内容不全的文件，而 .meta 已经删了——比没下载还糟。
            ch.force(true);
            commit(r, st, part, probe, l);
            return new Result(probe.total, probe.etag, st.allVerified(), transferred);
        }
    }

    private long chooseUnit(Request r, Probe probe) {
        if (r.integrity != null && r.integrity.hasChunks()) {
            // 有逐块摘要时，分块单位**必须**等于摘要的块大小，否则没法逐块验。
            return r.integrity.chunkSize;
        }
        int n = r.chunks > 0 ? r.chunks : defaultChunks;
        return Math.max(MIN_UNIT, (probe.total + n - 1) / n);
    }

    private long runChunks(Request r, ResumeState st, FileChannel ch, Probe probe,
                           Listener l, Cancellation c, AtomicLong done)
            throws IOException {
        List<Integer> todo = st.pending();
        if (todo.isEmpty()) return 0;

        final AtomicLong transferred = new AtomicLong();
        final AtomicReference<Throwable> firstError = new AtomicReference<>();
        final long t0 = System.nanoTime();
        int threads = Math.min(maxConcurrent, todo.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "msdl-chunk");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (final int idx : todo) {
                fs.add(pool.submit(() -> {
                    if (c.isCancelled() || firstError.get() != null) return;
                    try {
                        long s = st.startOf(idx), e = st.endOf(idx);
                        engine.fetchRange(urlFor(r, idx), s, e,
                                new PositionedChannel(ch, s), l, c);
                        st.mark(idx, ResumeState.DONE);

                        if (r.integrity != null && r.integrity.hasChunks()) {
                            String want = r.integrity.hashFor(idx);
                            if (want != null) {
                                String got = sha256Of(ch, s, e - s + 1);
                                if (!want.equalsIgnoreCase(got)) {
                                    // 验不过就退回 PENDING：下一轮（或下次启动）重下这一块。
                                    // 标成 DONE 会让坏数据一路混到最后的整份校验，那时已经
                                    // 分不清是哪一块坏的了。
                                    st.mark(idx, ResumeState.PENDING);
                                    l.onChunkVerifyFailed(idx, want, got);
                                    throw new IOException("第 " + idx + " 块校验不通过");
                                }
                                st.mark(idx, ResumeState.VERIFIED);
                            }
                        }
                        long n = st.endOf(idx) - st.startOf(idx) + 1;
                        transferred.addAndGet(n);
                        long soFar = done.addAndGet(n);
                        st.persist();
                        l.onChunkDone(idx, s, e);
                        l.onProgress(soFar, probe.total);
                        double sec = (System.nanoTime() - t0) / 1e9;
                        if (sec > 0.2) l.onSpeed(transferred.get() / sec);
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

    private void commit(Request r, ResumeState st, File part,
                        Probe probe, Listener l) throws IOException {
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
     * <p>{@link FileChannel#write(ByteBuffer, long)} 是带位置的写，不动通道自身的
     * position，因此多个线程可以共用同一个 FileChannel 各写各的区间——不用每个
     * worker 开一个文件句柄，也不用加锁。
     */
    static final class PositionedChannel implements WritableByteChannel {
        private final FileChannel ch;
        private long pos;
        private volatile boolean open = true;

        PositionedChannel(FileChannel ch, long base) { this.ch = ch; this.pos = base; }

        @Override public int write(ByteBuffer src) throws IOException {
            int n = ch.write(src, pos);
            pos += n;
            return n;
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }   // 不关共享通道
    }
}
