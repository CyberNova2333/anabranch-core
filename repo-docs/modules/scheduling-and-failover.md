# 多源调度与故障转移

在下载同一个文件时，纯靠单条源分块遇到卡顿或断流就会整体停滞。调度层把每个字节区间当作独立工作单元，在每次取块前根据实测吞吐与并发占用挑选镜像，并在单源失灵时控制原地重试、切源和运行期摘除。

关键约束在于：所有候选源必须能返回逐字节一致的目标文件；`Source.maxChunks` 目前仅保存在源描述对象上，尚未接入并发上限控制。

## 选源评分与权重模型

[源集合](../../src/io/anabranch/SourceSet.java) 在每次下载开始时调用 `resetDisabled()` 清空上一轮运行期禁用标记，然后按 `available()` 过滤出已启用且未被本轮摘除的源交由 [选源策略](../../src/io/anabranch/Policy.java) 选择：

| 内置策略 | 选源规则 | 首块冷启动行为 | 适用场景 |
| --- | --- | --- | --- |
| `Policy.fastestFirst()` | `score = (ema > 0 ? ema : weight * 1 MiB) / (1.0 + inFlight)` | 尚无吞吐样本时按静态权重折算初始速度 | 各镜像带宽差异明显 |
| `Policy.weighted()` | 按 `weight` 展开的原子游标加权轮询 | 直接按权重轮转，忽略实测速度差异 | 需要可控比例分流 |

每次 `fetchRange` 成功完成一段区间后，`SourceSet.recordSuccess` 用指数移动平均公式 `0.3 * bps + 0.7 * prevEma` 更新该源的 [只读统计](../../src/io/anabranch/Stats.java)，同时清零连续失败次数并记录最近成功时间；并发占用 `inFlight` 随单块借用与归还增减。因此即使某条镜像吞吐很高，一旦在途分片积压，`1.0 + inFlight` 分母也会把后续分片自动让渡给空闲备源。

## 失败预算、退避与熔断

当单块拉取抛出异常（含网络超时、区间长度不符或逐块 SHA-256 校验失败）时，[调度器核心](../../src/io/anabranch/Downloader.java) 的 `fetchOneChunk` 按三层护栏收口，而不是无止境空转：

1. **决策状态机**：默认失败判定为 `consecutiveFailures >= 3` 返回 `DISABLE_SOURCE`，第 `1` 次尝试失败返回 `RETRY_SAME`（原地重试），从第 `2` 次失败起返回 `SWITCH_SOURCE`。
2. **次数与时间预算**：每块最多尝试 `MAX_ATTEMPTS_PER_CHUNK = 6` 次；同时 `chunkRetryBudgetMs`（默认 `60_000` ms，`<= 0` 表示不限）**仅累计失败耗时与退避等待**，健康的慢速传输不会触发超时报错。
3. **指数退避与取消检查**：在允许重试前休眠 `Math.min(2000, 200L << Math.min(attempt - 1, 16))` ms（即 200ms、400ms、800ms、1600ms、上限 2000ms），每 `50` ms 轮询一次 [取消信号](../../src/io/anabranch/Cancellation.java)。

```java
SourceSet sources = new SourceSet(Arrays.asList(
    new Source("fast", 2, 0, true, key -> "https://fast.example.test/" + key),
    new Source("slow", 1, 0, true, key -> "https://slow.example.test/" + key)),
    Policy.fastestFirst());
```

## 典型边界案例

在 [多源真实 HTTP 测试](../../test/MultiSourceTest.java) 中验证了以下典型输入与边界行为：

- **单源连续失败摘除**：当镜像 `a` 始终返回 `503` 且存在备源 `b`、`c` 时，第 1 次分块失败原地重试并停在 `a`，第 2 次失败通过 `Listener.onSourceSwitch` 切至备源；累计 3 次连续失败后 `Stats.isDisabled(a)` 变为 `true`，剩余分块不再向 `a` 派发，但下一次调用 `download()` 前会重新重置启用状态。
- **卡住源触发重试预算**：当 `a` 对每段请求阻塞 4000ms 而引擎读超时为 1500ms、`chunkRetryBudgetMs = 2500` ms 且备源不可用时，失败尝试受预算拦截而提前结束并抛出带预算耗尽说明的异常（注：若首轮探测阶段即全部耗时失败，则直接以探测失败汇总抛错，不进入分块循环）。

## 对应实现与检查入口

相关代码分布在 [Source](../../src/io/anabranch/Source.java)、[SourceSet](../../src/io/anabranch/SourceSet.java)、[Policy](../../src/io/anabranch/Policy.java)、[Stats](../../src/io/anabranch/Stats.java)、[ChunkRef](../../src/io/anabranch/ChunkRef.java) 与 [Downloader](../../src/io/anabranch/Downloader.java)，行为由 [多源回归测试](../../test/MultiSourceTest.java) 通过真实本机 HTTP 服务验证。

需要跟踪分块如何落盘校验或查找调度修改入口，请继续阅读[断点续传与逐块校验](resume-and-integrity.md)与[代码地图](../code-map.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
