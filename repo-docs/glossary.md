# 术语表

| 术语 | 项目里的意思 | 延伸阅读 |
| --- | --- | --- |
| Anabranch | 取意“分出去又汇回来的支汊”，指把单文件拆分到多条镜像并行拉取并在校验后汇合成品 | [项目总览](README.md) |
| `Source` / `Source.Resolver` | 单条内容同构的镜像描述与逻辑 `key` 到具体 URL 的映射函数 | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `SourceSet` | 一组镜像源与选路策略的绑定容器，负责保存吞吐与失败统计、重置运行期禁用状态 | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `Policy` / `Stats` | 根据只读统计挑源（`pick`）和判定失败处置（`onFailure`）的策略接口与观测视图 | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `ChunkRef` | 单个分块的不可变描述对象，包含零基下标 `index` 与闭区间 `[start, endInclusive]` | [代码地图](code-map.md) |
| EMA 吞吐 | 指数移动平均速度（`0.3 * bps + 0.7 * prevEma`），防止偶然峰值锁死后续选路 | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `chunkRetryBudgetMs` | 单个分块累计失败尝试与退避等待的时间预算（默认 `60_000` ms，`<= 0` 不限） | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `MAX_ATTEMPTS_PER_CHUNK` / `MAX_BACKOFF_MS` | 单块硬性最大尝试次数（`6` 次）与指数退避单次等待上限（`2000` ms） | [多源调度与故障转移](modules/scheduling-and-failover.md) |
| `Request` / `Result` | 下载请求参数（逻辑名、目标文件、摘要、分块数、续传开关）与完成结果统计 | [一次真实运行](walkthroughs/one-real-run.md) |
| `Engine` / `HttpEngine` | 只负责探头与闭区间写入的传输接口，以及基于 `HttpURLConnection` 的零依赖实现 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |
| `Probe` / `ProbeReport` | 单源探测结果（总长、ETag、是否支持 Range）及 `probeAll` 返回的多源汇总对照 | [一次真实运行](walkthroughs/one-real-run.md) |
| `Listener` / `Cancellation` / `Listener.onChunkVerifyFailed` | 只读进度、切源与坏块验签失败回调接口，以及每 50ms 轮询一次的轻量取消信号 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |
| `ResumeState` / `<target>.meta` / `<target>.part` | 目标旁路临时数据文件 `<target>.part` 与头部为 `anabranch-meta/2` 的 `<target>.meta` 续传控制文件（含 `P/D/V` 位） | [断点续传与逐块校验](modules/resume-and-integrity.md) |
| `Integrity` / `wholeSha256` / `force(true)` / `part.renameTo(target)` | 调用方显式提供的逐块与可选整份摘要 `wholeSha256` 契约；`commit` 阶段先落盘刷写再替换成品 | [断点续传与逐块校验](modules/resume-and-integrity.md) |
| `IOException` | 传输、校验失败或重命名提交受阻时向调用方抛出的核心输入输出异常边界 | [一次真实运行](walkthroughs/one-real-run.md) |
| `RateLimiter` / `SpeedMeter` | 整次下载共享的写入侧令牌桶限速器与 3 秒（12×250ms）滑动窗口测速器 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |
| `Aria2Downloader` / `.a2part` / `runOnce` | 独立 `anabranch-aria2` AAR 的单线程 `runOnce` 驱动入口，直接由 libaria2 写入 `.a2part` 与 `.aria2` 续传 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |
| `.so` / `ANABRANCH_ARIA2_1.0` | 原生 `libaria2` 交叉编译共享库及其版本脚本导出的 8 个 JNI 接口与 `JNI_OnLoad` 符号版本 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |
| `BackendSelector` / `FileBackendSelector` | 通过 `aria2.backend` 与 `aria2.armed` 在 `aria2-openssl` 和 `aria2-gnutls` 间落盘切换的 dead-man's switch | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) |

证据状态：除特别标注外，本页基于当前源码已确认。
