# 一份文件怎样从多个源变成成品

下载成功需要回答两个不同问题：字节是否取齐，以及这些字节是否确实属于调用方期望的文件。这里跟踪纯 Java 路径：请求描述文件和摘要，下载器决定每块从哪里取，校验通过后再提交目标文件。

难点是一个块传输成功仍可能内容错误，必须把源可用、进度与可信摘要验证分开。

## Step 1: 调用方描述任务与可信摘要

调用方提供逻辑文件名、目标路径和源集合；每个源将逻辑名解析为自己的 URL。下面的中性调用形状来自现有 API，示例地址不是实际服务：

```java
Downloader dl = Downloader.newBuilder().build();
SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("a", "https://a.example.test/files/"),
    Source.ofBase("b", "https://b.example.test/files/")),
    Policy.fastestFirst());
Result result = dl.download(Request.of("file.bin", new File("file.bin")),
    sources, Listener.NONE, Cancellation.NONE);
```

该简写不提供摘要，不能证明混合源内容相同。需要内容保证时，用完整 `Request` 传入 `Integrity` 的逐块摘要及可选整份 SHA-256；摘要应由调用方信任的元数据提供。[请求类型](../../src/io/anabranch/Request.java)与[摘要类型](../../src/io/anabranch/Integrity.java)定义这一边界。

## Step 2: 探测源并判断能否复用进度

下载器依次找可用源，取得长度、ETag 和 Range 能力。`probeAll` 可提前查看所有源的探测结果；正常 `download` 使用先成功的源，不会自动证明所有镜像一致。

续传由 `.part` 保存数据、`.meta` 保存进度。长度、分块单位或同源 ETag 不匹配时丢弃旧状态；跨源不能拿另一台服务器的 ETag 比较，内容正确性仍需摘要支撑。服务端无 Range 时按整份单流处理，失败不保留不可复用的续传文件。见[下载准备阶段](../../src/io/anabranch/Downloader.java)与[续传状态](../../src/io/anabranch/ResumeState.java)。

## Step 3: 按策略取块并处理坏字节

策略根据统计挑源，引擎只取指定字节区间。某块失败会记录源失败，并受尝试次数、失败时间预算和退避控制；正常慢传输不会仅因失败预算而中断。限速在写入侧共享，针对整次下载。

提供块摘要时，取到数据先记已下，再比较摘要；不匹配回退为待取并重试，通过才标记已验。这个顺序防止“HTTP 成功”直接进入已验证状态。[调度与校验](../../src/io/anabranch/Downloader.java)中的 `fetchOneChunk` 路径和[真实 HTTP 测试](../../test/SingleSourceTest.java)可对照。

| 场景 | 结果与剩余边界 |
| --- | --- |
| 少字节或坏块摘要 | 失败并重试/换源；不能作为校验通过 |
| 提供整份摘要且最终不符 | 拒绝提交成品 |
| 未提供摘要 | 可完成传输，但没有调用方内容期望的证明 |
| 无 Range 且中断 | 不能续传，后续需重新整份下载 |

## Step 4: 完成所有块后提交成品

下载器先把文件通道落盘，再检查完整状态和可选整份摘要，随后替换目标并清除续传记录。当前实现先删除旧目标，再调用 `renameTo`；重命名失败会抛错，不能宣传为所有文件系统上的原子替换或完整断电事务。[提交实现](../../src/io/anabranch/Downloader.java)中的 `commit` 是最终结果边界。

独立 aria2 下载器共享请求类型，但当前不实现 `Engine`，也不进入 core JAR。更换核心引擎仍须遵守引擎不得反向依赖调度层的约束。

## Step 5: 用真实本机 HTTP 验证

```sh
sh tools/run-checks.sh
```

脚本编译 core/aria2 Java 部分，执行边界检查和四个测试主类。[单源测试](../../test/SingleSourceTest.java)覆盖半截传输、摘要和续传，[多源测试](../../test/MultiSourceTest.java)检查切源，[限速测试](../../test/ThrottleTest.java)检查整体速率。它们使用真实本机服务，不能替换成全 mock 后仍宣称相同覆盖。本轮未运行这些业务测试，未验证 Android 或 native 环境。

需要修改这条路径时，用[代码地图寻找责任与测试](../code-map.md)；要审查讲解的依据，查看[源码调查和边界](../references/source-evidence.md)。

证据状态：除特别标注外，本页基于当前源码已确认。
