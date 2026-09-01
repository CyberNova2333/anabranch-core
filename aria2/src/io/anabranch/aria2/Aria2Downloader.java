package io.anabranch.aria2;

import java.io.File;
import java.io.IOException;

import io.anabranch.Cancellation;
import io.anabranch.Listener;
import io.anabranch.Logger;
import io.anabranch.Request;
import io.anabranch.Result;
import io.anabranch.SourceSet;

/**
 * 形态 1（基线）：整文件、多 URI，分段与切源交给 aria2 自己（骨架，native 未接入）。
 *
 * <p>对外与 core 的 {@code Downloader} 同一公共面，使用者换后端不改调用代码。
 * 但有两条语义差异必须知道（详见 docs/ARIA2.md §二）：
 *
 * <ul>
 * <li>{@link SourceSet} 里的 {@code Policy}/{@code Stats} <b>不参与</b>——aria2
 *     有自己的选路与重试；{@code Source.weight} 映射不了，忽略并记日志。</li>
 * <li>逐块校验仍然是我们的：aria2 下完后、提交前按 {@code Integrity} 逐块自验，
 *     校验不过不提交——与 core 同一条铁律。</li>
 * </ul>
 */
public final class Aria2Downloader {

    private final File stateDir;
    private final BackendSelector selector;
    private final Logger log;

    private Aria2Downloader(Builder b) {
        this.stateDir = b.stateDir;
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

    /**
     * 计划中的时序：{@code selector.current()} → {@code Aria2Native.load}（失败则
     * {@code reportLoadFailure} 换后端重试一次）→ {@code arm} → session → addUri
     * （逐源 {@code urlFor(r.key)}）→ 轮询进度喂 {@link Listener} → 校验 → 提交
     * → 干净关停 → {@code disarm}。
     */
    public Result download(Request r, SourceSet sources, Listener l, Cancellation c)
            throws IOException {
        throw new UnsupportedOperationException(
                "native 侧未接入；集成计划见 docs/ARIA2.md §六");
    }
}
