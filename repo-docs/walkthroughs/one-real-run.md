# 一份文件怎样从多个源变成成品

一次下载任务需要回答两个互相独立的问题：所有字节是否已经取齐，以及这些字节是否确实属于调用方期望的文件。纯 Java 主路径把请求描述、多源统计、区间传输、状态持久化与最终替换拆成明确阶段，确保传输成功不会直接冒充内容可信。

整条链路的难点在于故障可能发生在任何一步：镜像响应超时、服务端忽略分块请求、同源文件内容更新，或跨镜像出现旧版本漂移。本页跟踪一次完整运行如何在正常完成与异常中止时守住成品边界。

## Step 1: 调用方提交请求与源集合

调用方首先准备目标文件路径、逻辑文件名和候选镜像集合，并可选择性附带逐块或整份摘要期望。如果目标地址只是简单前缀拼接，可使用便利构造；若镜像携带签名或不同目录结构，则通过解析回调按请求键动态生成地址。相关类型定义见[请求对象](../../src/io/anabranch/Request.java)、[源描述](../../src/io/anabranch/Source.java)与[摘要契约](../../src/io/anabranch/Integrity.java)。

```java
Downloader dl = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .build();
SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("mirror-a", "https://a.example.test/files/"),
    Source.ofBase("mirror-b", "https://b.example.test/files/")),
    Policy.fastestFirst());
Result result = dl.download(
    Request.of("archive.tar.gz", new File("out/archive.tar.gz")),
    sources, Listener.NONE, Cancellation.NONE);
```

注意简写工厂 `Request.of` 不会设置任何校验摘要，此时跨源切换无法证明两台服务器的文件字节一致；需要防篡改或跨源兜底时，应使用完整构造传入 `Integrity`。

## Step 2: 探测首条可用源并打开续传状态

进入[下载器主入口](../../src/io/anabranch/Downloader.java) 后，实现先调用 `SourceSet.resetDisabled()` 重置本轮运行期禁用标记，再依次调用 `Engine.probe` 查找第一条可用的镜像，取得总字节数、`ETag` 以及服务端是否支持 `Range` 分片。如果调用方希望提前排查镜像版本漂移，可先单独调用 `probeAll` 拿到 [探测汇总](../../src/io/anabranch/ProbeReport.java) 查看尺寸与标签是否一致，但正常 `download` 为减少等待仅以首条成功源为准。

紧接着，[续传状态机](../../src/io/anabranch/ResumeState.java) 检查目标文件同目录下的伴生文件 `<target>.part` 与头部魔数为 `anabranch-meta/2` 的 `<target>.meta`。只有总长度与分块单位匹配，且（在同源续传时）`ETag` 相同时，才会复用原有分块标记；若已切换为另一条源，则仅比对总长和块大小，跳过跨站无意义的 `ETag` 比较。若旧状态不匹配或服务端不支持区间下载，状态机新建或重置 `.part`/`.meta`，并在后续非可续传下载失败时于 `catch` 块中主动清理这两份临时文件。

## Step 3: 并发派发分块、校验坏块与退避重试

当服务端支持区间读取时，任务按 `Integrity.chunkSize`（若提供逐块摘要）或 `Math.max(256 KiB, ceil(total / chunks))` 划出固定区间列表，交由线程池并发拉取。每块下载经过 [调度模块](../modules/scheduling-and-failover.md) 与 [续传校验模块](../modules/resume-and-integrity.md) 协同推进，各阶段交接如下：

```text
Pending ('P') chunk
  ──▶ Policy.pick(candidates, stats, chunk)
  ──▶ Engine.fetchRange(url, start, end, channel, speedListener, cancel)
  ──▶ RateLimiter.acquire(...) + RandomAccess/FileChannel 落盘
  ──▶ 标记 Downloaded ('D')
  ──▶ (若含 chunkHash) SHA-256 校验:
        ├─ 匹配 ─▶ 标记 Verified ('V'), SourceSet.recordSuccess(EMA α=0.3)
        └─ 不符 ─▶ 回退 'P', Listener.onChunkVerifyFailed(...), 抛异常进退避/切源
```

任何一步失败都会累加该块失败耗时，在[调度器](../../src/io/anabranch/Downloader.java) 中受最多 `6` 次尝试、`chunkRetryBudgetMs`（默认 `60_000` ms，只计失败尝试与退避等待）以及上限 `2000` ms 指数退避制约；默认策略在第 1 次失败原地重试，第 2 次切换至备源，对某条源连续失败满 3 次则在本次下载期间将其禁用。整次下载的写入速率由全局共享的[令牌桶限速器](../../src/io/anabranch/RateLimiter.java) 控制。

## Step 4: 全量核验与替换提交成品

当全部块都达到完成要求后，提交阶段 `commit` 按固定顺序完成最终确认：首先对 `.part` 通道调用 `force(true)` 将脏页刷盘，再次检查全部块均已完成，并在存在 `wholeSha256` 时重算整份文件摘要；若整份摘要不匹配则直接抛出异常并保留隔离状态，绝不替换成品。确认无误后删除旧目标文件（若存在）、调用 `part.renameTo(target)` 改名落位、同步父目录句柄并清理 `.meta`，最后回调 `Listener.onProgress(total, total)`。

| 异常场景 | 处理分支与结果 | 对调用方可见的边界 |
| --- | --- | --- |
| 某块返回少字节或非零偏移请求却返 HTTP `200` | `HttpEngine` 立即抛 `IOException`，绝不推进分块状态 | 触发重试或切源；不会静默写入偏移错误的数据 |
| 第 `k` 块 SHA-256 核对失败 | 状态回退为 `'P'` 并触发 `onChunkVerifyFailed` | 坏块不会被保留为已完成，必须重下通过后方可提交 |
| 服务端不支持 `Range` 且传输中断 | 降级为整份单流后在异常分支删除 `.part`/`.meta` | 下次调用必须重新从头下载 |
| 所有镜像均已损坏或超过失败预算 | 抛出包含所有源报错详情或预算超限说明的异常 | 保留可续传 `.part`/`.meta` 以便排查恢复；成品未生成 |

## Step 5: 使用本机真实 HTTP 回路验证

```sh
sh tools/run-checks.sh
```

[检查脚本](../../tools/run-checks.sh) 不依赖 mock，直接以 JDK 标准库启动本地 HTTP 测试服务器执行[单源测试](../../test/SingleSourceTest.java)、[多源测试](../../test/MultiSourceTest.java)、[限速测试](../../test/ThrottleTest.java) 与 [原生故障切换测试](../../aria2/test/Aria2FailoverTest.java)。关于各目录下文件与独立 `aria2` 后端的详细职责索引，请继续查阅[代码地图](../code-map.md) 与 [源码证据审计](../references/source-evidence.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
