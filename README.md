# Anabranch · 多源分块下载

> English: [README.en.md](README.en.md)
>
> **anabranch**：河流从主河道分出去、下游又汇回来的那种支汊。数据分成多股走不同
> 线路、最后汇回同一个文件——名字是字面意思，不是比喻。
>
> 一个文件、N 条线路、按块调度：分片从哪条源取是运行时按实测吞吐决定的，
> 某条线路失败就把那一块换条源重取，全程逐块校验。
>
> 纯 Java、零第三方依赖、core 不碰 Android。

## 引入

预定的 Maven Central 坐标（**尚未发布**，发布步骤见 [docs/RELEASING.md](docs/RELEASING.md)）：

```
io.anabranch:anabranch-core:0.1.0
```

Java 8 字节码；Android 上 minSdk 21，不需要 core library desugaring。

## 怎么跑

```bash
sh tools/run-checks.sh    # Java 8 编译 → 全量编译 → core 边界 → 三套测试
```

core 的三个套件都**真起本地 HTTP 服务器、真下文件**；多源那套起三台，可以随时
把某台切成「全挂 / 慢 / 服务 N 块后掉线 / 接了连接不回数据」。第四套
（`Aria2FailoverTest`）测 aria2 后端的 dead-man's switch，纯文件 IO、不碰网络。

覆盖的不是「能下下来」，而是几种会静默出错的路径：

| 用例 | 在防什么 |
|---|---|
| 中途断开会抛错而不是悄悄成功 | 服务端少给字节时把半块当整块提交 |
| 失败后留下 .meta 供续传 | 一轮失败把已下好的块全作废 |
| 逐块校验能拦下坏块 / 报出来的是第 2 块 | 坏块混到最后的整份校验，那时分不清是哪一块 |
| 坏块不会被提交成成品 | 校验失败还是 rename 成了目标文件 |
| etag 变了仍能正确下完 | **服务端换了文件而续传接着写——两个版本拼接** |
| 不支持 Range 时能整份下完 | 对老服务端直接报错而不是退化 |
| 分片真的散到多条源 | 说是多源，实际全压在第一条上 |
| 有源挂掉仍能下完 / 没从挂掉的源拿到区间 | 一条线路坏了整次下载就失败 |
| 全挂时错误里点名了三条源 | 只报最后一条的原因，查不出到底哪里不通 |
| **换源之后能续传、没因 etag 不同而整份重下** | 见下面「跨源续传」 |
| 慢源拿到的块少于快源之和 | 选路不看实测，等于没有多源 |
| 策略规则直测（重试→换源→拉黑、快源优先、压满让位） | 端到端测不准：结果取决于谁先抢到块 |
| **卡住的源被时间预算兜住** | 每次尝试熬满读超时，次数上限把代价乘上去 |
| 重试之间确实退避了 | 对着已经出问题的服务器猛敲 |

## 设计文档

完整设计（含尚未实现的多源那部分）见 [`docs/DESIGN.md`](docs/DESIGN.md)。

## 文件

```
src/io/anabranch/Downloader.java    调度：分块、并发、重试循环、校验、提交
src/io/anabranch/Engine.java        引擎接口：只负责「取一个字节区间」
src/io/anabranch/HttpEngine.java    HttpURLConnection 实现，零第三方依赖
src/io/anabranch/Source.java        一条线路；Resolver 决定「逻辑文件 → 该源上的 URL」
src/io/anabranch/SourceSet.java     一组源 + 运行时观测（EMA 吞吐、连败、在途数）
src/io/anabranch/Policy.java        选路与失败处置：fastestFirst / weighted
src/io/anabranch/Integrity.java     整份 + 逐块摘要
src/io/anabranch/ResumeState.java   .part/.meta 续传状态，格式显式且带版本号
src/io/anabranch/RateLimiter.java   令牌桶限速（整次下载一份额度）
src/io/anabranch/SpeedMeter.java    最近 3 秒滑动窗口测速
其余为小的值类型与回调接口（Request/Result/Probe/Listener/Stats/…）

test/SingleSourceTest.java   单源：分块、续传、校验、Range 缺失（21 项）
test/MultiSourceTest.java    多源：选路、切源、跨源续传、预算、退避（33 项）
test/ThrottleTest.java       限速与测速（13 项）

aria2/                       anabranch-aria2 后端（独立 artifact，不进 core jar）：
                             Aria2Downloader 已实现，28 项真下载测试载入真 libaria2
                             跑（sh aria2/native/run-host-tests.sh）；dead-man's
                             switch 带 10 项测试。见 docs/ARIA2.md
```

## 三个刻意的决定

**core 不引用任何 `android.*`。** 所以桌面 JVM 上就能跑测试。这不是顺手——下载
调度、分块、续传、校验全是纯逻辑，一旦掺进 Context / Handler / SharedPreferences
就再也没法脱机验证。Android 相关的东西（前台服务、通知、WorkManager 接线）应该
是另一个 artifact。

**引擎只管「取一段字节」，不管重试与切源。** 这条边界是同时支持纯 Java HTTP 和
native aria2 的前提：两者对「取一段」都能实现，对调度的看法却完全不同。把调度
留在引擎里，换引擎就等于换掉整套重试策略——那正是原实现里最难改的地方。

**`.meta` 第一行就是版本号，认不出一律重来。** 宁可重下，也不要拿不确定的状态
去拼文件。

## 跨源续传的坑

**同样的字节在不同服务端上 etag 完全可能不同**（nginx 用 mtime+size、S3 用 md5、
CDN 自己再包一层）。若拿 A 源的 etag 去跟 B 源的比，每次换源都会把好好的续传状态
判成失效、整份重下。

所以 `.meta` 里记下「这份 etag 是从哪条源探来的」，只有**同一条源**再探时才比 etag；
跨源时退回到只比长度，正确性由逐块摘要兜底。同理，`probeAll` 把**尺寸不一致当错误、
etag 不一致只当提示**——尺寸不同才是真的不同。

## 限速与测速

`bytesPerSecond(n)` 限的是**整次下载**，不是每个分片——每个分片各限一份的话，
并发数一变实际速率就跟着变：设了 1MB/s、开 4 个分片就成了 4MB/s。

实现上限速做在**写入侧**（包装交给 `Engine` 的那个 channel），所以对任何引擎实现
都自动生效，也不用把限速塞进 `Engine` 接口。令牌桶容量正好是一秒的量：短暂空闲后
可以立刻用掉一秒额度，但攒不出十几秒的量——那样一次突发就能把移动流量咬掉一块。

`onSpeed` 报的是**最近 3 秒**的滑动窗口，不是「开始到现在的平均」。平均值在界面上
是误导的：下载十分钟后突然断流，平均速度还是很好看，进度条却不动了。

## 重试与退避

一块失败只影响这一块，别的线程继续跑。同一块最多试 6 次，试之间**指数退避**
（200ms 起、封顶 2 秒，等待期间响应取消）——立刻重试等于对着一台已经出问题的
服务器猛敲，它多半正忙着或正在重启，而你的重试让它更忙。

次数上限之外还有**时间预算**（`chunkRetryBudgetMs`，默认 60 秒）。光有次数是不够的：
一条**卡住**的源——不是拒绝，是接了连接却不回数据——每次尝试都要熬满引擎的读超时，
次数上限只会把代价乘上去（30 秒超时 × 6 次 = 一个分片卡住三分钟，期间一个字节都没
往前走）。预算只算**失败**尝试与退避的耗时，正在正常传输的尝试不会被打断——
慢不等于坏。

## 选路

`Policy.fastestFirst()`（默认）按实测吞吐的 EMA 选，除以 `1 + inFlight` 防止最快
那条被一次性压满、其余线路带宽闲置。没有样本的源用静态权重当先验，好让它有机会
被试一次。失败时先原地重试再换源，连错三次拉黑——多数失败是瞬时的，立刻换源会让
一条好线路因为一次抖动就被冷落。

`Policy.weighted()` 是纯静态权重轮转，可预测，适合测试。策略只读 `Stats`、不碰 IO，
所以能造一份假 Stats 直接单测。

## 已知的欠账（第 1 步刻意没做）

- **aria2 后端**：还没做，将来必须是独立 artifact（见「许可」）。
- **不支持 Range 时无法续传**：退化成单流整份下，中断就得重来。这是协议限制，
  没法绕过（失败时至少不再留下用不上的 `.part`/`.meta` 了）。
- **目录 fsync** 用的是「建一个临时文件再 sync」的土办法，Java 没有直接 API。
  Android 上可以走 `ParcelFileDescriptor`，届时在 android artifact 里覆盖。

## 参与开发

```bash
sh tools/install-hooks.sh     # 只有 Agent 没碰过这份克隆时才需要
sh tools/run-checks.sh        # 编译 + core 边界 + 77 项测试（约 1 分钟）
```

规范见 [CONTRIBUTING.md](CONTRIBUTING.md)；AI 协作者的硬约束见
[CLAUDE.md](CLAUDE.md) 与 [AGENTS.md](AGENTS.md)。

`tools/check-core-boundaries.py` 守着 core 的四条边界：不碰 Android、零第三方依赖、
不用 API 24+ 才有的运行时类库、分层不倒挂。四条的理由写在那个脚本开头。

## 许可

**Apache-2.0**（见 [LICENSE](LICENSE)）——比 MIT 多一条专利授权，商业项目敢用。

⚠ 将来加 `anabranch-aria2` 时**必须单独一个 artifact**：aria2 是 GPLv2+，
静态链接后分发二进制有源码提供义务，不能让 core 的使用者被动继承。
