# CONTRIBUTING.md

> 给人看的版本。Agent 的硬约束在 [`AGENTS.md`](AGENTS.md) 与 [`CLAUDE.md`](CLAUDE.md)，
> 三者冲突时：`CLAUDE.md` > `AGENTS.md` > 本文件。

## 一分钟上手

```bash
git clone …/anabranch-core && cd anabranch-core
sh tools/install-hooks.sh        # 只有 Agent 没碰过这份克隆时才需要
sh tools/run-checks.sh           # 编译 + core 边界 + 67 项测试，约 1 分钟
```

没有 Gradle、没有 Maven、没有依赖下载。`javac` 和 `python3` 就够了——这是刻意的，
见下面「为什么没有构建系统」。

## 分支

直接在 `main` 上改。唯一的例外是 `hotfix/*`（`main` 红了、修复需要几轮试错），
修好合回、删掉。

一个维护者的库不需要分支隔离，开分支的成本在这里全是净损失。

## 提交

```
fix(engine): 修正 Range 被忽略时的误判

服务端无视 Range 直接返回 200 时，原来会照单全收——于是文件开头被写进了中间，
下载「成功」但内容是坏的。现在遇到 200 且 start>0 直接抛。

顺带记一句排查过程：一开始怀疑是并发写串了位置，加了每线程独立 FileChannel
也没用；真正的判据是响应码，不是写入路径。

Co-authored-by: Claude <noreply@anthropic.com>
文档: 纯行为修正，README 的描述仍然准确，不影响
```

四个要素，钩子会硬拦前三个：

1. **Conventional 前缀 + 中文标题**（type 见 `AGENTS.md` §1 一）
2. **`Co-authored-by`**：实际执笔的 Agent，完整 `Name <email>`
3. **「文档:」一行**：写明对应文档改动，或明确写不影响
4. **正文写 why**：diff 已经说了 what

作者按**实际执笔的人**记，没有默认值。Agent 永远只做 co-author。

## 代码约定

### core 的四条边界（`tools/check-core-boundaries.py` 会拦）

1. 不引用 `android.*` / `androidx.*`
2. 不引入任何非 `java.*` 的包
3. 不用 `java.util.function` / `stream` / `Optional` / `nio.file` / `time`
   （API 24 之前的 Android 上不存在，脱糖救不了）
4. `Engine` 的实现不许引用调度层（`Downloader` / `Policy` / `SourceSet` / …）

理由都写在那个脚本的开头，改之前先读那一段。

### 注释写「为什么」，不写「是什么」

代码已经说了是什么。值得写进注释的是：

- 这里为什么不用更显然的那个写法；
- 这个常量为什么是这个值（`EMA 的 α=0.3`、`连错三次才拉黑`）；
- 踩过的坑与它的表现形式（「服务端无视 Range 返回 200 时照收会静默损坏」）。

这个库现有的注释密度偏高，是刻意的：下载这类东西的失败模式大多是**静默**的，
读代码的人看不出哪一行在防什么。新代码请保持同样的密度。

### 测试不许 mock 掉网络

真起 `com.sun.net.httpserver`、真传字节。要防的失败模式在 mock 里全都不会发生。

新增行为时，问自己「它坏掉的时候是什么样子」，然后写那个用例——而不是写
「它正常时是什么样子」。

## CI

`.github/workflows/checks.yml` 在 push 到 `main` 时跑，两个 job：

- **编译与测试**：JDK 17 与 21 各跑一遍 `tools/run-checks.sh`，然后**再复跑两轮**
  ——涉及并发与真实超时的用例在负载高的 runner 上最容易露馅，一次绿不能说明不 flaky。
- **提交信息**：只查本次推送新增的提交，与 `pre-push` 同一个语义（历史不翻旧账）。

CI 跑的就是你本地那条命令，没有第二套配置——所以本地绿了 CI 基本不会红，
反过来也一样。

## 为什么没有构建系统

`javac src/io/anabranch/*.java` 就能编译，`java -cp out SingleSourceTest` 就能跑测试。
加 Gradle 会带来 wrapper、依赖解析、版本对齐一整套，而这个库目前**没有任何依赖**。

等到要发到 Maven Central、或者要加 `anabranch-aria2`（含 native 产物）时再加，
那时它才开始付出与成本相称的价值。

## 发布与许可

- `anabranch-core`：Apache-2.0。
- 将来的 `anabranch-aria2` **必须是独立 artifact**——aria2 是 GPLv2+，静态链接后
  分发二进制有源码提供义务，不能让只用纯 Java 那条路的人被动继承。

## 一件请注意的事

这个库是独立实现。设计经验来自早先在另一个项目里的实践，但代码是重写的。
**不要从任何其他仓库复制代码进来**，也不要在注释、文档或提交信息里写那个项目的
名字——只说「早先踩过的坑」这类角色描述就够了。

理由是读代码的人要的信息一点没少：他要知道的是「这个常量是拿真实故障换来的」，
出处叫什么对他没有增量；而写上去之后，这个仓库就替别处做了它自己没做的宣告。
