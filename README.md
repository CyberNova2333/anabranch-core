# Anabranch · 第 1 步骨架：单源分块下载

> **anabranch**：河流从主河道分出去、下游又汇回来的那种支汊。数据分成多股走不同
> 线路、最后汇回同一个文件——名字是字面意思，不是比喻。
>
> 这是 API 草案第八节里说的第 1 步：**先不做多源**，把单源分块、断点续传、
> 逐块校验跑通并且能独立验证。多源建立在「单源分块是对的」之上；反过来先做
> 多源，一旦拼出坏文件，分不清是调度错了还是分块错了。

## 怎么跑

```bash
javac -d out src/io/anabranch/*.java test/SingleSourceTest.java
java -Dstdout.encoding=UTF-8 -cp out SingleSourceTest
```

测试**真起一个本地 HTTP 服务器、真下文件**，21 项全绿。它覆盖的不是「能下下来」，
而是几种会静默出错的路径：

| 用例 | 在防什么 |
|---|---|
| 中途断开会抛错而不是悄悄成功 | 服务端少给字节时把半块当整块提交 |
| 失败后留下 .meta 供续传 | 一轮失败把已下好的块全作废 |
| 逐块校验能拦下坏块 / 报出来的是第 2 块 | 坏块混到最后的整份校验，那时分不清是哪一块 |
| 坏块不会被提交成成品 | 校验失败还是 rename 成了目标文件 |
| etag 变了仍能正确下完 | **服务端换了文件而续传接着写——两个版本拼接** |
| 不支持 Range 时能整份下完 | 对老服务端直接报错而不是退化 |

## 设计文档

完整设计（含尚未实现的多源那部分）见 [`docs/DESIGN.md`](docs/DESIGN.md)。

## 文件

```
src/io/anabranch/Msdl.java          公共值类型与回调（Probe/Result/Integrity/Request/Listener…）
src/io/anabranch/Engine.java        引擎接口：只负责「取一个字节区间」
src/io/anabranch/HttpEngine.java    HttpURLConnection 实现，零第三方依赖
src/io/anabranch/ResumeState.java   .part/.meta 续传状态，格式显式且带版本号
src/io/anabranch/Downloader.java    调度：分块、并发、校验、提交
test/SingleSourceTest.java  端到端测试
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

## 多源接进来的位置

只有一处：`Downloader.urlFor(Request, int chunkIndex)`，现在是恒等函数。
换成 `SourceSet.pick(...)` 返回的那条源的 URL 即可，其余（分块、续传、校验、
提交）一行不用动。

## 已知的欠账（第 1 步刻意没做）

- **块级重试**：现在一块失败就整轮失败。有 `.meta` 兜底所以不丢进度，
  但应当在 worker 内先重试几次——这件事要和切源策略一起设计，留给第 2 步。
- **限速**：没有。
- **`onSpeed` 是全局平均**，不是滑动窗口。多源要按源统计 EMA，第 2 步一起改。
- **不支持 Range 时无法续传**：退化成单流整份下，中断就得重来。这是协议限制，
  但至少不该像现在这样把 `.part` 也留着——第 2 步清掉。
- **目录 fsync** 用的是「建一个临时文件再 sync」的土办法，Java 没有直接 API。
  Android 上可以走 `ParcelFileDescriptor`，届时在 android artifact 里覆盖。

## 许可

建议 **Apache-2.0**——比 MIT 多一条专利授权，商业项目敢用。
建仓库时用 GitHub 的 license 选择器直接生成 `LICENSE`，别手抄（抄错了比没有还麻烦）。

⚠ 将来加 `anabranch-aria2` 时**必须单独一个 artifact**：aria2 是 GPLv2+，
静态链接后分发二进制有源码提供义务，不能让 core 的使用者被动继承。
