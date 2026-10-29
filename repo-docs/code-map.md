# 代码地图

先读[一次真实操作的阶段交接](walkthroughs/one-real-run.md)，再按这里的职责定位。覆盖范围为 纯 Java 下载器从请求、源探测到分块校验与成品提交；这不是全仓库架构清单。

| 路径 | 职责 | 关键代码 | 与主流程的关系 |
| --- | --- | --- | --- |
| `src/io/anabranch/` | 纯 Java 公开契约和下载主流程 | [下载器](../src/io/anabranch/Downloader.java)、`Request`、`Result`、`Listener`、`Cancellation` | 进入下载、通知进度、返回结果 |
| `src/io/anabranch/` 的策略与状态 | 选源、统计、续传、校验与限速 | [策略](../src/io/anabranch/Policy.java)、`SourceSet`、`Stats`、`ResumeState`、`Integrity`、`RateLimiter` | 按块决策，保留进度并限制提交 |
| `src/io/anabranch/` 的传输 | 探测和取范围字节 | [引擎契约](../src/io/anabranch/Engine.java)、`HttpEngine`、`Probe`、`ProbeReport` | 引擎不引用调度层 |
| `test/` | 本机真实 HTTP 测试支持 | [单源](../test/SingleSourceTest.java)、[多源](../test/MultiSourceTest.java)、[限速](../test/ThrottleTest.java) | 验证坏块、切源和真实耗时 |
| `tools/` 的检查入口 | 编译及边界检查 | [检查脚本](../tools/run-checks.sh)、`check-core-boundaries.py` | 组合 Java 8 编译和回归，钩子实现不在指南范围 |
| `aria2/` | 独立后端及 native 包装 | [Java 入口](../aria2/src/io/anabranch/aria2/Aria2Downloader.java)、`native/`、`test/` | 相邻独立 artifact，仅作边界索引 |

## `src/io/anabranch/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `Downloader.java` | 探测、分块、失败重试和最终提交 | `download` / `fetchOneChunk` / `commit` | 库调用方；单源/多源/限速测试 |
| `ResumeState.java` / `Integrity.java` | 续传状态与可选摘要契约 | `matches` / `PENDING` / `VERIFIED` | Downloader；摘要和续传回归 |
| `Engine.java` / `HttpEngine.java` | 探测并取指定区间 | `probe` / `fetchRange` | Downloader；真实本机 HTTP 测试 |

## `test/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `SingleSourceTest.java` | 坏块、续传、ETag 与无 Range | `testChunkVerifyCatchesCorruption` / `testNoRangeServer` | tools/run-checks.sh |
| `MultiSourceTest.java` / `ThrottleTest.java` | 多源和整体写侧限速回归 | 测试主类 `main` | tools/run-checks.sh；真实 HTTP |

## `aria2/`

| 重要代码 | 功能 | 关键符号 | 调用方 / 使用方 |
| --- | --- | --- | --- |
| `aria2/src/io/anabranch/aria2/Aria2Downloader.java` | 独立后端入口，仅作边界定位 | `Aria2Downloader` | 独立 artifact，不进入 core JAR |
| `aria2/test/Aria2FailoverTest.java` / `aria2/native/` | 文件 IO 回归及真实 native 独立验证 | `Aria2FailoverTest`；native 流程暂缓 | run-checks 与 run-host-tests 是不同检查 |

## 修改入口与验证

分块重试、提交或写入限速从 `Downloader` 开始，分别对照单源/多源/限速测试。修改选路同时检查 `Policy`、`Stats` 与 `SourceSet`，纯策略规则用构造统计验证，端到端不赌调度顺序。修改续传检查 `ResumeState` 的源身份、长度与单位，并核对跨源与 ETag 回归。

发布元数据在 `build.gradle.kts` 和 `docs/RELEASING.md`；日常正确性入口仍是 `tools/run-checks.sh`。Gradle `check` 只配置三套 core 主类，不等同于该脚本包含的 aria2 文件 IO 回归。

## 覆盖范围

aria2 独立 artifact、JNI/native 构建与 Android 真机接线仅列入口，不追踪其实现；Maven 发布、包签名和历史 bundle 导入不在主流程范围。

源码链接指向仓库文件。独立文档站不携带这些文件；可按表中的精确路径在克隆中查找。需要确认哪些代码已经逐段核对，查看[证据覆盖记录](references/source-evidence.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
