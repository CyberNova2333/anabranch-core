# 引擎接口与 aria2 边界

Anabranch 把核心下载库与可选原生后端完全拆成两个独立产物：`src/io/anabranch/` 只写纯 Java 8 字节码、零第三方依赖、`AGPL-3.0-only` 许可；而基于 `libaria2` 的原生 JNI 加速放在相邻的 `aria2/` 目录，由于静态链接 GPLv2+ 的上游库，二进制分发整体适用 `GPL-3.0-or-later`。

这种拆分不仅为了许可证隔离，也防止仅需几百 KB 纯 Java 下载功能的宿主被迫引入原生 `.so` 体积与进程崩溃面。

## 纯 Java 引擎契约与 `HttpEngine`

核心层通过 [Engine 接口](../../src/io/anabranch/Engine.java) 与传输协议解耦，任何自研传输实现（如接入 OkHttp）只需完成两个方法：

| 契约方法 | 输入输出要求 | 必须遵守的失败护栏 |
| --- | --- | --- |
| `probe(url, cancel)` | 返回长度、`ETag` 与 `rangeSupported` | 优先 `HEAD`，失败时回退 `GET Range: bytes=0-0` |
| `fetchRange(url, start, endInclusive, out, l, c)` | 向 `WritableByteChannel` 写入闭区间精确字节 | 遇到非零起始偏移却返 HTTP `200` 或长度不符时必须抛 `IOException` |

默认实现 [HttpEngine](../../src/io/anabranch/HttpEngine.java) 使用 `HttpURLConnection`，默认连接超时 `15_000` ms、读取超时 `30_000` ms、`User-Agent` 为 `"anabranch/0.1"`，固定发送 `Accept-Encoding: identity` 以免中间压缩破坏字节偏移。写入侧限速由 [RateLimiter](../../src/io/anabranch/RateLimiter.java) 提供整次下载共享的令牌桶（`1` 秒桶容量、每次最多休眠 `50` ms 并轮询取消），瞬时速率由 [SpeedMeter](../../src/io/anabranch/SpeedMeter.java) 按 `12 × 250ms`（总计 `3000ms`）滑动窗口平滑上报。

## 核心四条硬边界

[核心边界检查脚本](../../tools/check-core-boundaries.py) 与 `javac --release 8` 共同守住 `src/io/anabranch/` 全部 19 个源文件：

1. 不导入任何 `android.*` 类库；
2. 零第三方依赖，仅限标准库；
3. 不使用 Java 9+ 或 Android API 24+ 才存在的标准库（例如 `java.util.function` 与 `java.nio.file`），保证 `minSdk 21` 无需开 desugaring；
4. `Engine`、`HttpEngine`、`Probe`、`ProbeReport` 禁止反向依赖调度器与状态类型（`Downloader`、`SourceSet`、`Policy`、`Stats`、`ResumeState`）。

## 可选 `aria2` 后端的独立契约

```java
Aria2Downloader a2 = Aria2Downloader.newBuilder()
    .stateDir(new File(cacheDir, "anabranch-a2"))
    .build();
Result res = a2.download(req, sources, listener, cancellation);
```

[Aria2Downloader](../../aria2/src/io/anabranch/aria2/Aria2Downloader.java) 复用了 core 的 `Request`、`SourceSet`、`Listener`、`Cancellation` 与 `Result`，但在内部模型上刻意独立：

- **为何不是 `Engine`**：2026-09-01 实测 `aria2 1.37.0` 发现，对其强塞自定义 `Range` 头会导致它误按响应 `Content-Range` 总长持续等待挂起，因此原 `Aria2Engine` 设计已淘汰删除，改由 libaria2 公开 session API 整文件接管多 URI（`addUri`）。
- **单线程会话与互斥**：JNI 底层 [Aria2Native](../../aria2/src/io/anabranch/aria2/Aria2Native.java) 由 Java 单线程反复调用 `runOnce` 驱动，全局通过 `synchronized (BUSY)` 串行化以规避原生状态竞态；取消在两圈 `runOnce` 之间触发 `sessionShutdown`，进度回调按 `200ms` 节流，停机回收预算为 `10_000ms`。
- **调度与续传语义差异**：`Aria2Downloader` 不走 `Policy`、`Stats` 与 `Source.weight`，临时文件名使用 `.a2part` 与 `.a2part.aria2`；下载完成后仍用核心 `Integrity` 做校验，**校验失败必删 `.a2part`/`.aria2`，网络异常则保留供下次续传**。
- **双 TLS 后端 dead-man's switch**：[FileBackendSelector](../../aria2/src/io/anabranch/aria2/FileBackendSelector.java) 通过 `stateDir` 下的 `aria2.backend` 与 `aria2.armed`（带 `fsync`）在 `aria2-openssl` 与 `aria2-gnutls` 间切换；因两份 `.so` 导出同名 `ANABRANCH_ARIA2_1.0` JNI 符号，进程内加载后即锁定该次生命周期，加载失败（`UnsatisfiedLinkError`）当场切备选，运行期 native 崩溃则在下次启动消费 `armed` 标记切换。

需要按职责定位具体源文件或回顾整条下载主线，请继续查看[代码地图](../code-map.md)与[一次真实运行](../walkthroughs/one-real-run.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
