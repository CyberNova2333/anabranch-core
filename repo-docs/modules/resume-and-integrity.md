# 断点续传与逐块校验

多源并发下载最隐蔽的风险不是断线报错，而是两个镜像版本漂移或只下了一半的损坏分片混进最终成品。纯 Java 核心用同目录伴生文件 `.part` 与 `.meta` 记录分块生命周期，把「字节拿到了」和「字节可信」拆成两个状态位，只在全部校验通过后才替换目标文件。

注意：**调用方不传入摘要时**，下载器只能保证收齐声明的长度与同源 `ETag` 一致性，无法证明不同镜像上的文件内容完全相同。

## 分块粒度与 `.meta` v2 落盘格式

当服务端支持 `Range` 且请求允许续传（`Request.allowResume == true`）时，[下载器入口](../../src/io/anabranch/Downloader.java) 按以下规则计算固定分块大小 `unit`：

- 若提供了逐块摘要（`Integrity.hasChunks()`），直接采用 `r.integrity.chunkSize`，保证分块边界与摘要数组下标严格对齐。
- 否则取 `Math.max(256 * 1024, (probe.total + n - 1) / n)`，其中 `n` 优先取 `Request.chunks > 0`，否则取构建器默认值 `8`。

[续传状态机](../../src/io/anabranch/ResumeState.java) 在目标文件同目录写入 `<target>.part` 与 `<target>.meta`，头魔数固定为 `anabranch-meta/2`，字段按行记录：`total`（目标总长）、`etag`、`source`（首轮探测命中的源 `id`）、`unit`（分块字节数）、`count`（总块数）以及逐块状态字符数组 `state`。每次持久化先写入 `.meta.tmp` 并执行 `fsync`，再原子或替换重命名为 `.meta`，并在目录创建/清理 `.anabranch-dsync` 以尽量同步目录项。

## 同源 `ETag` 与跨源续传契约

| 续传比较场景 | 长度与 `unit` | `ETag` 检查 | 安全前提与后续动作 |
| --- | --- | --- | --- |
| 同一条源继续下载 | 必须完全一致 | 当 `probeSourceId` 非空且等于 `meta.source` 时，必须相等 | 不等则整份作废旧 `.part`/`.meta` 并重下 |
| 切换到另一条镜像续传 | 必须完全一致 | 不跨源比较 `ETag`（各 CDN 生成规则不同） | 保留已完成分块；内容一致性依赖 `Integrity` |
| 服务端不支持 `Range` 或禁续传 | — | 不生成复用契约 | 失败时 `catch` 块主动删掉 `.meta` 与 `.part` |

## 逐块状态迁移与提交顺序

每块的状态字符在 `.meta` 中只允许按 `P`（待下载 `PENDING`）→ `D`（已下载 `DOWNLOADED`）→ `V`（已校验 `VERIFIED`）推进；在存在逐块 SHA-256 时严格遵循以下顺序：

1. `Engine.fetchRange` 将该区间字节写入 `.part` 对应偏移并 `flush`，此时才标记为 `D`。
2. 若 [摘要契约](../../src/io/anabranch/Integrity.java) 为该块提供了期望摘要，计算实际读取区间的 SHA-256：不匹配则立即回退为 `P`、回调 `Listener.onChunkVerifyFailed`，并抛出异常进入重试或切源，绝不标为 `V`；匹配则标为 `V` 并记录源吞吐。
3. 全部块完成且未触发全文件中断时，`commit` 先对 `.part` 的 `FileChannel` 调用 `force(true)`，再确认 `ResumeState.isComplete()`；若存在 `wholeSha256`，全文件算一遍 SHA-256，不符直接抛错并拒绝替换。
4. 校验全部通过后执行 `target.delete()`、`part.renameTo(target)`、`ResumeState.syncDir(...)` 与 `st.discard()` 删除 `.meta`，最后触发 `onProgress(total, total)`。

```java
Integrity integrity = new Integrity(
    262_144L,
    new String[] { chunk0Sha256Hex, chunk1Sha256Hex },
    wholeFileSha256Hex
);
Request req = new Request("archive.zip", new File("out/archive.zip"), integrity, 8, true);
```

## 典型边界案例

在 [单源真实 HTTP 测试](../../test/SingleSourceTest.java) 与 [多源测试](../../test/MultiSourceTest.java) 中已覆盖以下边界：

- **坏块拦截**：故意篡改第 2 块返回字节时，`Listener.onChunkVerifyFailed` 精确报告索引 `1`（即第 2 块），不会落成最终目标文件。
- **同源 `ETag` 变更作废**：同源在两次下载间更改响应 `ETag` 后，第二轮重新下载全部字节（`transferredBytes == total`）而非盲目复用旧 `.part`。
- **目标替换非跨文件系统强事务**：`commit` 先删除旧目标再调用 `File.renameTo`；若重命名失败会抛出 `IOException`，旧目标可能已删除。

如需查看传输接口如何拦截服务端忽略 `Range` 以及可选 `aria2` 后端的校验时机差异，请继续阅读[引擎接口与 aria2 边界](engine-and-aria2-boundary.md)与[代码地图](../code-map.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
