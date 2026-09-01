package io.anabranch.aria2;

/**
 * libaria2 的 JNI 表面（骨架）：C++ API 的最小子集，native 侧尚未接入。
 *
 * <p>方法集刻意小：session 建立/关停、挂一条多 URI 下载、轮询进度。libaria2
 * 的其余能力（BT、metalink、RPC）这个后端用不上，不声明——JNI 表面每多一个
 * 函数，两个 TLS 后端的 `.so` 就各多一处要维护的胶水。
 *
 * <p>约定：options 用扁平的 {@code String[]}（key, value 交替），不用 Map——
 * JNI 侧解 Map 的样板代码远多于解数组，而调用方只有本包。
 */
final class Aria2Native {

    private Aria2Native() {}

    /**
     * 载入指定后端的 `.so`（如 {@code aria2-openssl} → {@code libaria2-openssl.so}）。
     * {@code UnsatisfiedLinkError} 由调用方捕获并走 {@link BackendSelector#reportLoadFailure}。
     */
    static void load(String backend) { System.loadLibrary(backend); }

    /** 建 session。返回 native 句柄；0 表示失败。 */
    static native long sessionNew(String[] globalOptions);

    /** 请求干净关停（等价 aria2 的 shutdown）；之后仍需 run 到返回 0 才算停完。 */
    static native void sessionShutdown(long session);

    /** 释放 session。只能在 run 返回 0 之后调用。 */
    static native void sessionRelease(long session);

    /** 挂一条下载：同一文件的 N 个镜像 URI。返回 gid；null 表示失败。 */
    static native String addUri(long session, String[] uris, String[] options);

    /** 跑一步事件循环。返回 1 表示还有事做，0 表示已停。 */
    static native int runOnce(long session);

    /** 进度：{completedBytes, totalBytes, downloadSpeed}；gid 不存在返回 null。 */
    static native long[] progressOf(long session, String gid);

    /** 该 gid 的错误描述；没出错返回 null。 */
    static native String errorOf(long session, String gid);
}
