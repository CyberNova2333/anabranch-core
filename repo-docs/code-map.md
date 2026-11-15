# 代码地图

请先阅读[一次真实运行全流程](walkthroughs/one-real-run.md)建立主干时序，再按本页职责定位需要改动的源文件与测试套件。全仓源码主要划分为纯 Java 下载核心、真实 HTTP 测试套件、检查脚本与可选原生 `aria2` 后端四大区域。

| 路径 | 职责 | 关键代码 | 与主流程的关系 |
| --- | --- | --- | --- |
| `src/io/anabranch/` | 纯 Java 8 下载调度、选源、续传、校验与传输 | [Downloader](../src/io/anabranch/Downloader.java)、[SourceSet](../src/io/anabranch/SourceSet.java)、[HttpEngine](../src/io/anabranch/HttpEngine.java) | 承担核心多源分块下载全部运行期逻辑 |
| `test/` | 本地真实 HTTP 模拟服务器驱动的回归测试集 | [SingleSourceTest](../test/SingleSourceTest.java)、[MultiSourceTest](../test/MultiSourceTest.java)、[ThrottleTest](../test/ThrottleTest.java) | 验证断点续传、坏块拦截、切源熔断与限速精度 |
| `aria2/` | 可选原生 libaria2 Java 胶水层、JNI 实现及专属测试 | [Aria2Downloader](../aria2/src/io/anabranch/aria2/Aria2Downloader.java)、[FileBackendSelector](../aria2/src/io/anabranch/aria2/FileBackendSelector.java) | 独立 AAR 产物，共享请求接口但接管整文件多 URI 下载 |
| `tools/` | Java 8 编译、核心依赖边界校验与 Git 钩子 | [run-checks.sh](../tools/run-checks.sh)、[check-core-boundaries.py](../tools/check-core-boundaries.py) | 守住零依赖、API 21 兼容性与单向依赖边界 |

## `src/io/anabranch/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `Downloader.java` | 下载编排、线程池分块调度、退避预算与成品提交 | `newBuilder` / `download` / `probeAll` / `commit` | 上层应用；全部三套核心测试 |
| `Request.java` / `Result.java` | 不可变任务参数描述与交付字节/校验汇总 | `Request.of` / `allowResume` / `chunkVerified` | 调用方、`Downloader`、`Aria2Downloader` |
| `Source.java` / `SourceSet.java` | 镜像 URL 解析回调、运行期禁用状态与 EMA 吞吐跟踪 | `Source.Resolver` / `recordSuccess` / `available` | `Downloader`、`Policy`、`Aria2Downloader` |
| `Policy.java` / `Stats.java` / `ChunkRef.java` | 选源算法、失败处置状态机及分块区间元数据 | `fastestFirst` / `weighted` / `Decision` | `Downloader`、`MultiSourceTest` |
| `ResumeState.java` / `Integrity.java` | `.part`/`.meta` v2 格式读写与逐块/整份 SHA-256 契约 | `anabranch-meta/2` / `matches` / `hashFor` | `Downloader`、`SingleSourceTest` |
| `Engine.java` / `HttpEngine.java` / `Probe.java` / `ProbeReport.java` | 只负责探头与闭区间写入的传输接口及默认 JDK 实现 | `probe` / `fetchRange` / `Accept-Encoding: identity` | `Downloader`、`SingleSourceTest` |
| `RateLimiter.java` / `SpeedMeter.java` / `Listener.java` / `Cancellation.java` / `Logger.java` | 令牌桶限速、3 秒滑窗测速、事件回调、轮询取消与日志 | `acquire` / `onSourceSwitch` / `isCancelled` | `Downloader`、`HttpEngine`、`ThrottleTest`、`Aria2Downloader` |

## `test/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `SingleSourceTest.java` | 单源探测、半截断连续传、第 2 块坏块拦截、ETag 变更作废、无 Range 降级 | `main`（21 项断言） | `tools/run-checks.sh`、`build.gradle.kts` |
| `MultiSourceTest.java` | 多源分片散列、挂源切换、全挂汇总、预算超限拦截与退避测量 | `main`（33 项断言） | `tools/run-checks.sh`、`build.gradle.kts` |
| `ThrottleTest.java` | 写入侧全局限速令牌桶速率收敛与取消及时性测试 | `main`（13 项断言） | `tools/run-checks.sh`、`build.gradle.kts` |

## `aria2/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `aria2/src/io/anabranch/aria2/Aria2Downloader.java` | 串行 `runOnce` 会话驱动、`.a2part` 管理与后置 `Integrity` 校验 | `newBuilder().stateDir(...)` / `download` | 选用原生后端的 Android 应用；`Aria2DownloaderTest` |
| `aria2/src/io/anabranch/aria2/BackendSelector.java` / `FileBackendSelector.java` | `aria2.backend` 与 `aria2.armed` 落盘切换 `openssl`/`gnutls` | `current` / `arm` / `disarm` / `reportLoadFailure` | `Aria2Downloader`、`Aria2FailoverTest` |
| `aria2/src/io/anabranch/aria2/Aria2Native.java` & `aria2/native/` | 9 个导出 JNI 入口、`compat_arm.c` 兜底及 NDK/宿主构建脚本 | `ANABRANCH_ARIA2_1.0` / `build-aria2.sh` / `package-aar.sh` | `Aria2Downloader`、`run-host-tests.sh`、`build-aria2.yml` |
| `aria2/test/Aria2FailoverTest.java` / `Aria2DownloaderTest.java` | dead-man's switch 文件协议单测与宿主真下载全路径测试 | `Aria2FailoverTest`（10 项）/ `Aria2DownloaderTest`（28 项） | `tools/run-checks.sh`、`aria2/native/run-host-tests.sh` |

## `tools/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `tools/run-checks.sh` | 串联 `--release 8` 编译、边界扫描及四套 Java 主类回归测试 | `javac --release 8` | 本地提交前检查、`.github/workflows/checks.yml` |
| `tools/check-core-boundaries.py` | 静态核对 `src/io/anabranch/` 19 个文件的包依赖与单向分层 | `FORBIDDEN_IMPORTS` | `tools/run-checks.sh` |
| `tools/check-commit-messages.py` / `tools/githooks/` | 强制检查提交标题格式、`文档:` 行、`Co-authored-by` 尾注与大文件守卫 | `_msgrules.py` / `commit_msg.py` | 本地 Git Hooks、`.github/workflows/checks.yml` |

## 修改入口与验证

涉及分块划分、退避预算或提交顺序的改动从 `Downloader.java` 入手，对应检验 `SingleSourceTest` 与 `MultiSourceTest`；调整 `SourceSet` 评分、禁用条件或 `Policy` 时同步核对 `Stats` 读写契约；任何修改 `Engine` 或 `HttpEngine` 的提交都必须保持对调度层零反向依赖，并在变动后重新运行 `sh tools/run-checks.sh`。

修改 `aria2/` 原生胶水、符号表或构建脚本后，Java 侧文件切换协议仍受 `tools/run-checks.sh` 覆盖，但真实 `libaria2` 会话驱动需在具备完整编译环境的宿主上运行 `sh aria2/native/run-host-tests.sh` 验证。仓库打包与 Central 发布任务定义在 [Gradle 脚本](../build.gradle.kts) 与 [发布手册](../docs/RELEASING.md)，不用于日常单测开发。

## 覆盖范围

本代码地图覆盖 `src/io/anabranch/` 核心库、`test/` 回归测试、`aria2/` 原生后端 Java 与 JNI 胶水入口以及 `tools/` 检查脚本；Maven Central 上传自动化（`.github/workflows/build-core.yml`、`setup-signing-key.yml`）与原生交叉编译流水线（`build-aria2.yml`）均属于构建与发布基础设施，各自以对应 YAML 与 [发布手册](../docs/RELEASING.md) 为权威来源。核查所有源码声明的检验依据请查看[源码证据审计](references/source-evidence.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
