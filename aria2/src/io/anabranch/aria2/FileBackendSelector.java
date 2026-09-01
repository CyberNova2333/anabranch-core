package io.anabranch.aria2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@link BackendSelector} 的文件实现：`stateDir` 下两个小文件。
 *
 * <pre>
 *   aria2.backend   当前偏好的后端名
 *   aria2.armed     armed 标记，内容是武装时的后端名
 * </pre>
 *
 * <p>消费时序（{@link #current()} 发现 armed 标记时）：先把偏好写成<b>另一个</b>
 * 后端，再删标记。顺序是刻意的——切换要粘住，否则每次启动来回抖；若在两步之间
 * 再次崩溃，下次启动重复同一流程，结果一致，协议幂等。两个后端都坏时会交替尝试：
 * 总得选一个，交替至少不会永远吊死在同一个坏后端上。
 *
 * <p>{@link #arm} 里做 fsync：armed 标记是崩溃检测的全部依据，写进页缓存还没
 * 落盘时整机断电，下一轮就会在同一个坏后端上再死一次。偏好文件不 fsync——
 * 它丢了只是回到默认偏好，不影响正确性。
 *
 * <p>不用 java.nio.file：这个 artifact 与 core 一样受 minSdk 21 约束。
 */
public final class FileBackendSelector implements BackendSelector {

    private final File prefFile;
    private final File armedFile;
    private final String backendA;
    private final String backendB;

    /** @param stateDir 状态目录，不存在会创建；两个后端名必须不同。 */
    public FileBackendSelector(File stateDir, String backendA, String backendB) {
        if (backendA == null || backendB == null || backendA.equals(backendB)) {
            throw new IllegalArgumentException("需要两个不同的后端名");
        }
        stateDir.mkdirs();
        this.prefFile = new File(stateDir, "aria2.backend");
        this.armedFile = new File(stateDir, "aria2.armed");
        this.backendA = backendA;
        this.backendB = backendB;
    }

    @Override public synchronized String current() {
        String armed = read(armedFile);
        if (armed != null) {
            // 上一轮 arm 了却没 disarm——死于非命。换到另一个并把切换粘住。
            // 标记内容认不出时（半截写入、手工改坏）当作 backendA 处理：
            // 保守地切到 B 而不是崩掉——这个类的职责就是在坏状态下也给出答案。
            String next = other(known(armed) ? armed : backendA);
            write(prefFile, next, false);
            armedFile.delete();
            return next;
        }
        String pref = read(prefFile);
        return known(pref) ? pref : backendA;
    }

    @Override public synchronized void arm(String backend) {
        require(backend);
        write(armedFile, backend, true);   // fsync：这个标记是崩溃检测的全部依据
    }

    @Override public synchronized void disarm() {
        // 干净关停：armed 判词作废，同时把「这个后端好用」粘进偏好。
        String armed = read(armedFile);
        if (known(armed)) write(prefFile, armed, false);
        armedFile.delete();
    }

    @Override public synchronized void reportLoadFailure(String backend) {
        require(backend);
        // 加载期失败是当场捕获的，进程还活着——直接改偏好，armed 标记与此无关
        //（调用方还没 arm 或者会重新 arm）。
        write(prefFile, other(backend), false);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private boolean known(String b) { return backendA.equals(b) || backendB.equals(b); }

    private void require(String b) {
        if (!known(b)) throw new IllegalArgumentException("不认识的后端：" + b);
    }

    private String other(String b) { return backendA.equals(b) ? backendB : backendA; }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8").trim();
        } catch (IOException e) {
            return null;   // 读不出来就当没有——这个类要在坏状态下也给出答案
        }
    }

    private static void write(File f, String value, boolean sync) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes("UTF-8"));
            if (sync) out.getFD().sync();
        } catch (IOException e) {
            // 写不进去（磁盘满、目录被删）没有可用的补救；吞掉会让崩溃检测
            // 静默失效，抛出去让调用方知道这台设备上 failover 保护不可用。
            throw new IllegalStateException("写不进后端状态文件：" + f, e);
        }
    }
}
