# 公开 API 与扩展契约

本页整理 `io.anabranch` 核心包的稳定接口约定、自定义扩展必须守住的边界，以及选用独立包 `io.anabranch.aria2` 时必须了解的语义差异。

## 核心公共接口契约（`io.anabranch`）

| 类型 | 核心方法 / 字段 | 契约说明 |
| --- | --- | --- |
| `Downloader` | `download(Request, SourceSet, Listener, Cancellation)`<br>`download(Request, String url, Listener, Cancellation)`<br>`probeAll(String key, SourceSet, Cancellation)` | 线程安全可复用；每次 `download` 开始都会重置 `SourceSet` 运行期禁用标记 |
| `Request` | `key`, `target`, `integrity`, `chunks`, `allowResume`<br>`Request.of(key, target)` | `chunks <= 0` 时退回构建器默认分块数；简写 `of` 不含摘要、允许续传 |
| `Result` | `totalBytes`, `etag`, `chunkVerified`, `transferredBytes` | `etag` 非空（无则返回 `""`）；`transferredBytes` 记录本次实际网络读入字节，可用于区分续传与重下 |
| `Source` | `id`, `weight` (`>=1`), `maxChunks`, `enabled`, `Resolver`<br>`Source.ofBase(id, base)` / `Source.ofUrl(id, url)` | **调用方须确保同组内各源指向逐字节相同的文件**；注：`maxChunks` 目前仅存元数据，暂未做单源并发拦截 |
| `SourceSet` | `SourceSet(List<Source>, Policy)` / `SourceSet.single(url)` | 默认采用 `Policy.fastestFirst()`；持有各源的只读运行期 `Stats` |
| `Integrity` | `chunkSize`, `chunkHashes[]`, `wholeSha256`<br>`Integrity.wholeOnly(sha256)` | 摘要均为小写十六进制 SHA-256 字符串；存在 `chunkHashes` 时分块大小固定对齐 `chunkSize` |
| `ProbeReport` | `ok`, `failed`, `sizesAgree`, `size`, `etagsAgree`, `anyUsable()` | 探测汇总结构；`etagsAgree == false` 仅为提示信息，不影响续传启用 |
| `Listener` | `onTotal`, `onProgress`, `onSpeed(double bytesPerSec)`,<br>`onChunkDone`, `onChunkVerifyFailed`, `onSourceSwitch` | 默认方法全为 no-op，提供 `Listener.NONE`；单位严格为**字节/秒**，不做 MiB/MB 换算 |
| `Cancellation` | `boolean isCancelled()` / `Cancellation.NONE` | 引擎读取、令牌桶限速及退避等待期间每隔约 50ms 轮询检查一次 |

## 扩展点一：自定义 `Engine`（例如接入 OkHttp / Cronet）

只要严格守住单向依赖边界，调用方无需修改核心包即可通过 `Downloader.newBuilder().engine(myEngine)` 接入自己的网络栈：

```java
public final class OkHttpEngine implements Engine {
    @Override
    public Probe probe(String url, Cancellation c) throws IOException {
        // 1. 先发 HEAD 读取 Content-Length / ETag / Accept-Ranges == bytes
        // 2. 不确定或受限时回退 GET Range: bytes=0-0（注意按实际字节返回 Probe）
        // 3. 禁止引入 Downloader / SourceSet / Policy / Stats / ResumeState
    }

    @Override
    public void fetchRange(String url, long start, long endInclusive,
                           WritableByteChannel out, Listener l, Cancellation c) throws IOException {
        // 1. 若 start >= 0 请求头需带 Range: bytes=start-endInclusive 及 Accept-Encoding: identity
        // 2. 若 start > 0 但服务端返回 HTTP 200（无视 Range），必须直接抛出 IOException
        // 3. 写入字节数不等于 (endInclusive - start + 1) 时必须抛 IOException
        // 4. 循环读写期间检查 c.isCancelled()，取消及时退出并抛异常
    }
}
```

**严禁破坏的四条硬边界**（由 `tools/check-core-boundaries.py` 强制校验）：
- `src/io/anabranch/` 禁止导入 `android.*` 与任何第三方库；
- 禁止使用 Java 9+ / Android API 24+ 运行时类库；
- `Engine`、`HttpEngine`、`Probe`、`ProbeReport` 禁止引用 `Downloader`、`SourceSet`、`Policy`、`Stats`、`ResumeState`。

## 扩展点二：自定义 `Policy` 与 `Source.Resolver`

### `Source.Resolver`

当同一批镜像存在路径差异、对象存储预签名 URL 或短期鉴权 Token 时，请不要用 `Source.ofBase`，而是直接构造 `new Source(id, weight, maxChunks, enabled, key -> ...)`。每次构造请求和切源重连都会实时回调 `urlFor(key)`。

### `Policy`

```java
Policy custom = new Policy() {
    @Override
    public Source pick(List<Source> candidates, Stats stats, ChunkRef chunk) {
        // 读取 stats.emaBytesPerSec(s)、stats.inFlight(s)、
        // stats.consecutiveFailures(s)、stats.lastSuccessAtMillis(s) 挑出一条候选源
    }

    @Override
    public Decision onFailure(Source s, ChunkRef chunk, Throwable e, int attempt, Stats stats) {
        // 返回 RETRY_SAME / SWITCH_SOURCE / DISABLE_SOURCE / ABORT
    }
};
```

返回 `Decision.ABORT` 会跳过剩余重试预算立刻终止当前块并使 `download()` 失败；返回 `DISABLE_SOURCE` 会将源标记为本轮禁用并交由其他可用备源继续。

## `Aria2Downloader` 的语义差异契约

虽然 `io.anabranch.aria2.Aria2Downloader` 对外暴露与核心完全一致的 `download(Request, SourceSet, Listener, Cancellation)` 签名，切换后端几乎无需改动调用点，但以下三条运行语义契约**与纯 Java 核心明显不同**：

1. **内部选路权让渡**：`Aria2Downloader` 将 `SourceSet` 中所有可用源通过 `urlFor(key)` 解析成 URI 列表一次性交给 `libaria2` 的 `addUri`。**core 的 `Policy`、`Stats` 与 `Source.weight` 全部不参与调度**，仅映射 `Request.chunks > 0` 为 aria2 `split` 参数。
2. **全局串行互斥**：受限于 `libaria2` session 模型，`Aria2Downloader.download` 在进程内部通过 `synchronized (BUSY)` 严格串行化，避免跨线程/并发 session 竞争导致底层状态损坏。
3. **整份下完后再自验**：`Aria2Downloader` 不启用 aria2 自带的 checksum 选项，而是在下载至 `.a2part` 后按传入 `Integrity` 统一核验；**网络失败保留 `.a2part` 与 `.a2part.aria2` 续传，校验失败则两者全部删除防毒害后续续传**。

## 扩展开发 FAQ

### 1. 为什么 `Cancellation` 采用轮询接口而不是 `Thread.interrupt()`？
如果库内响应取消依赖中断线程，会在调用方线程池复用线程上遗留未清除的中断标志位，导致下游无关的阻塞 I/O（如数据库或文件清理）意外炸出 `InterruptedException`。采用显式布尔轮询接口，让调用方在网络读取、令牌桶等待和退避窗口之间以确定周期干净退出。

### 2. `Listener` 回调会在哪个线程触发？是否做了防抖？
纯 Java 核心中，`onProgress`、`onSpeed` 与 `onSourceSwitch` 直接在工作线程池回调，要求监听实现保持轻量且线程安全。而在 `Aria2Downloader` 中，进度与速度由驱动 `runOnce` 的单线程以 `200ms` 节流周期触发（`CALLBACK_INTERVAL_MS = 200`）。

### 3. `Source.maxChunks` 可以限制单条 CDN 的连接数吗？
**当前不能。** `Source` 上虽然声明了 `public final int maxChunks` 字段（用于描述元数据和未来扩展预留），但现行 `Downloader` 与内置 `Policy` 尚未读取该字段做单源并发上限裁剪；现阶段要压低高占源压力，依赖 `Downloader.Builder.maxConcurrent(int)` 全局上限与 `Policy.fastestFirst()` 中的 `1.0 + inFlight` 分母调节。
