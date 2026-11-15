# 调度熔断、续传与校验机制

本页深入说明纯 Java 核心的三大内部运转协议：按实时吞吐动态选源、失败时间预算下的退避熔断、`anabranch-meta/2` 断点续传与校验提交顺序，以及原生后端的进程外 `dead-man's switch`。

## 多源调度与故障转移

### 选源公式与 EMA 平滑

每次下载启动前，`Downloader` 调用 `SourceSet.resetDisabled()` 清空上一轮的禁用标志。之后调度器按如下策略择源：

- **`Policy.fastestFirst()`（默认）**：评分函数为 `(ema > 0 ? ema : s.weight * 1024L * 1024L) / (1.0 + inFlight)`。其中 `ema` 吞吐率在每块写入成功后通过 `0.3 * bps + 0.7 * prevEma` 更新，既能感知带宽突变，又避免偶然一次快传峰值长期锁死选路；分母 `1.0 + inFlight` 会在快源并发积压时将剩余块让给空闲次优源。
- **`Policy.weighted()`**：通过原子游标按 `Source.weight` 权重轮转分发，适合人工固定切流比例的场景。

### 三重失败防线与预算计算

在 `fetchOneChunk` 中，单块拉取遇到异常按以下参数限制重试：

| 防护参数 | 源码常量 / 默认值 | 真实含义与边界 |
| --- | --- | --- |
| 最大尝试次数 | `MAX_ATTEMPTS_PER_CHUNK = 6` | 单块最多尝试 6 次（跨所有源累计） |
| 单块失败时间预算 | `chunkRetryBudgetMs = 60_000` ms | **仅累加「失败耗时 + 退避等待」**；健康慢速下载永远不会因预算报错 |
| 指数退避窗口 | `min(2000, 200 << min(attempt - 1, 16))` ms | 依次约 200ms、400ms、800ms、1600ms、封顶 2000ms；等待时每 50ms 测一次取消 |
| 默认失败状态机 | `RETRY_SAME` → `SWITCH_SOURCE` → `DISABLE_SOURCE` | 第 1 次原地重试；第 2 次切备源；单源连续失败满 3 次本轮摘除 |

## 断点续传（`.meta` v2）与提交顺序

### 伴生文件与文本格式

在支持 `Range` 且 `Request.allowResume == true` 的情况下，`ResumeState` 会在目标文件同目录维护 `<target>.part`（实际数据空洞/随机写文件）与 `<target>.meta`（状态元文件）：

```text
anabranch-meta/2
total=1048576
etag="v2-abc"
source=mirror-a
unit=262144
count=4
state=VVPD
```

- **块大小 `unit` 计算**：若 `Integrity.hasChunks()` 为真，严格取 `r.integrity.chunkSize`；否则取 `Math.max(262_144, (total + chunks - 1) / chunks)`（单块不小于 256 KiB）。
- **块状态字符 `state`**：`P`（`PENDING` 待下载）、`D`（`DOWNLOADED` 已写 `.part` 未校验）、`V`（`VERIFIED` 块校验通过）。
- **持久化防半截落盘**：写入状态时先写入 `.meta.tmp` 并对文件描述符调用 `fsync`，再重命名为 `.meta`，并在父目录生成/清理临时标记 `.anabranch-dsync` 同步目录项。

### 同源 `ETag` 与跨源续传决策

| 前提条件 | `matches(...)` 校验规则 | 若不匹配怎么处理 |
| --- | --- | --- |
| `total` 与 `unit` | 必须与当前探头返回尺寸及计算分块单位完全一致 | 旧 `.part`/`.meta` 作废，重新整份下载 |
| **同源继续下载** | 当前 `probeSourceId` 非空且等于 `meta.source` 时，`etag` 必须一致 | 视为服务端文件已变更（如无提示覆写），作废重下 |
| **切换新镜像续传** | 当 `probeSourceId` 不同于 `meta.source` 时**跳过 `etag` 比较** | 保留已完成块；字节同构安全性由 `Integrity` 逐块 SHA-256 保障 |

### 逐块校验与 `commit` 收尾顺序

```text
fetchRange 写入 .part ──▶ 标记 'D'
   └─▶ 若含 chunkHash: 计算该块 SHA-256
         ├─ 不匹配 ─▶ 回退 'P', 通知 onChunkVerifyFailed, 抛出异常进切源/重试
         └─ 匹配   ─▶ 标记 'V', 计入 SourceSet 成功速度
全部块达到要求 ──▶ commit():
   1. partChannel.force(true) 刷盘
   2. 再次验证 st.isComplete()
   3. 若存在 wholeSha256: 对 .part 算一遍整份 SHA-256（不符直接抛错拒绝替换）
   4. target.delete() ──▶ part.renameTo(target) ──▶ syncDir ──▶ 删除 .meta ──▶ onProgress(total, total)
```

> **实现注意**：若 `commit` 在 `target.delete()` 成功之后但在 `part.renameTo(target)` 时因权限或跨文件系统移动受阻失败，方法会抛出 `IOException`，旧目标可能已不可恢复，此时 `.part` 仍保留在同目录供手工或重试接管。

## 原生 `aria2` 的进程外 `dead-man's switch`

使用 `anabranch-aria2` 时，原生层一旦触发 `SIGSEGV` 或 `SIGABRT` 会直接杀掉整个 JVM/Android 进程，Java 的 `try/catch` 毫无记录机会。为此，`FileBackendSelector` 将状态判据挪出内存，落盘于 `stateDir`：

1. **启动前武装（`arm(backend)`）**：向 `aria2.armed` 写入当前后端名（`aria2-openssl` 或 `aria2-gnutls`）并执行 `fsync`。
2. **正常停机拆弹（`disarm()`）**：只有在 `sessionShutdown` 且 `DRAIN_BUDGET_MS = 10_000` 内确认收尾完成后才删除 `aria2.armed`；停不干净宁可不拆弹，让下一次启动自动换后端。
3. **跨启动消费翻转（`current()`）**：若进程重启时发现 `aria2.armed` 依然存在，说明上一轮发生了非正常死亡，选择器立即将 `aria2.backend` 翻转为另一个后端并删除 `aria2.armed`。由于「先改偏好、后删标记」，即便中间二次崩溃协议依然幂等。
4. **加载期即时切源（`reportLoadFailure`）**：若 `System.loadLibrary` 抛出可捕获的 `UnsatisfiedLinkError`，当场在同一次 `download()` 中切去备选后端加载，不必等待下轮重启。
