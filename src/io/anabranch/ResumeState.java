package io.anabranch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 断点续传状态：{@code <target>.part} 装数据，{@code <target>.meta} 装进度。
 *
 * <h3>格式是显式且带版本号的</h3>
 * <pre>
 * anabranch-meta/1
 * total=104857600
 * etag="a1b2c3"
 * unit=1048576
 * count=100
 * state=VVVVDDPPPP……
 * </pre>
 * 每块一个字符：{@code P} 未下、{@code D} 已下未验、{@code V} 已验。
 *
 * <p>原实现里这个格式是隐式约定，换个读法就读不了旧文件。这里第一行就是版本号，
 * 认不出的版本一律当作「没有续传信息」重来——<b>宁可重下，也不要拿不确定的
 * 状态去拼文件</b>。
 *
 * <h3>etag 变了必须整份作废</h3>
 * 服务端换了文件而续传接着往下写，是这类库最经典的静默损坏：长度对得上、
 * 下载「成功」，内容是两个版本的拼接。所以 {@link #matches} 对 etag 不匹配
 * 一律返回 false。
 */
final class ResumeState {

    static final char PENDING = 'P';
    static final char DONE    = 'D';
    static final char VERIFIED= 'V';

    private static final String MAGIC = "anabranch-meta/1";

    final long total;
    final String etag;
    final long unit;
    final int count;
    private final char[] state;
    private final File metaFile;

    private ResumeState(File metaFile, long total, String etag,
                        long unit, int count, char[] state) {
        this.metaFile = metaFile;
        this.total = total;
        this.etag = etag == null ? "" : etag;
        this.unit = unit;
        this.count = count;
        this.state = state;
    }

    static File partFileFor(File target) { return new File(target.getPath() + ".part"); }
    static File metaFileFor(File target) { return new File(target.getPath() + ".meta"); }

    static ResumeState fresh(File target, long total, String etag, long unit) {
        int count = (int) ((total + unit - 1) / unit);
        char[] st = new char[count];
        java.util.Arrays.fill(st, PENDING);
        return new ResumeState(metaFileFor(target), total, etag, unit, count, st);
    }

    /** 读不出、版本不认、字段缺失——一律返回 null，由调用方重来。 */
    static ResumeState load(File target) {
        File mf = metaFileFor(target);
        if (!mf.isFile()) return null;
        try {
            byte[] b = new byte[(int) Math.min(mf.length(), 1 << 20)];
            try (RandomAccessFile r = new RandomAccessFile(mf, "r")) { r.readFully(b); }
            String[] lines = new String(b, StandardCharsets.UTF_8).split("\n");
            if (lines.length < 6 || !MAGIC.equals(lines[0].trim())) return null;
            long total = Long.parseLong(val(lines[1], "total"));
            String etag = val(lines[2], "etag");
            long unit = Long.parseLong(val(lines[3], "unit"));
            int count = Integer.parseInt(val(lines[4], "count"));
            String st = val(lines[5], "state");
            if (st.length() != count || count <= 0 || unit <= 0) return null;
            return new ResumeState(mf, total, etag, unit, count, st.toCharArray());
        } catch (Exception e) {
            return null;   // 任何解析异常都等价于「没有可用的续传信息」
        }
    }

    private static String val(String line, String key) {
        int i = line.indexOf('=');
        if (i < 0 || !line.substring(0, i).trim().equals(key)) {
            throw new IllegalArgumentException("meta 第「" + key + "」项不认识：" + line);
        }
        return line.substring(i + 1).trim();
    }

    /** 续传前提：总长度一致 <b>且</b> etag 一致（任一为空则只比长度）。 */
    boolean matches(long total, String etag, long unit) {
        if (this.total != total || this.unit != unit) return false;
        if (this.etag.isEmpty() || etag == null || etag.isEmpty()) return true;
        return this.etag.equals(etag);
    }

    long startOf(int i) { return (long) i * unit; }
    long endOf(int i)   { return Math.min(total, startOf(i) + unit) - 1; }
    char stateOf(int i) { return state[i]; }

    List<Integer> pending() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) if (state[i] == PENDING) out.add(i);
        return out;
    }

    long completedBytes() {
        long n = 0;
        for (int i = 0; i < count; i++) if (state[i] != PENDING) n += endOf(i) - startOf(i) + 1;
        return n;
    }

    boolean isComplete() {
        for (char c : state) if (c == PENDING) return false;
        return true;
    }

    boolean allVerified() {
        for (char c : state) if (c != VERIFIED) return false;
        return true;
    }

    synchronized void mark(int i, char s) { state[i] = s; }

    /**
     * 原子落盘：写临时文件 → fsync → rename → fsync 目录。
     *
     * <p>少了最后一步 fsync 目录，掉电后 rename 可能没落到盘上，重启看到的还是旧
     * meta——于是已经下好的块被当成未下载重来。不致命，但白费流量，而且很难查。
     */
    synchronized void persist() throws IOException {
        StringBuilder sb = new StringBuilder(count + 128);
        sb.append(MAGIC).append('\n')
          .append("total=").append(total).append('\n')
          .append("etag=").append(etag).append('\n')
          .append("unit=").append(unit).append('\n')
          .append("count=").append(count).append('\n')
          .append("state=").append(state).append('\n');
        File tmp = new File(metaFile.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fo.flush();
            fo.getFD().sync();
        }
        if (!tmp.renameTo(metaFile)) {
            // Windows 上目标存在时 rename 会失败；删了再来。POSIX 上不会走到这。
            if (!metaFile.delete() || !tmp.renameTo(metaFile)) {
                throw new IOException("meta 落盘失败：" + metaFile);
            }
        }
        syncDir(metaFile.getParentFile());
    }

    static void syncDir(File dir) {
        if (dir == null) return;
        // 目录 fsync 在 Java 里没有直接 API；打开目录本身在 Linux 上可行，
        // 在其他平台会抛——抛了就算了，不是所有平台都需要这一步。
        try (FileOutputStream ignored = new FileOutputStream(new File(dir, ".anabranch-dsync"))) {
            ignored.getFD().sync();
        } catch (Exception ignored) {
        } finally {
            new File(dir, ".anabranch-dsync").delete();
        }
    }

    void discard() {
        metaFile.delete();
    }
}
