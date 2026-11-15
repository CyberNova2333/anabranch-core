---
layout: home

hero:
  name: Anabranch Core
  text: Java 8 与 Android 多源分块下载库
  tagline: 零第三方依赖、适配 minSdk 21 的纯 Java 分块调度核心，支持多镜像竞速、失败切源、跨源续传与逐块 SHA-256 校验，另有独立封装的可选 aria2 JNI 后端。
  actions:
    - theme: brand
      text: 起步与集成
      link: /docs/
    - theme: alt
      text: 架构与源码导览
      link: /docs/architecture
    - theme: alt
      text: GitHub 仓库
      link: https://github.com/CyberNova2333/anabranch-core

features:
  - title: 多源并发分块调度
    details: 支持用 Policy.fastestFirst()（基于 EMA 吞吐与并发数评分）或 Policy.weighted() 把单个文件拆分派发给多个同内容镜像。
  - title: 失败切源与预算熔断
    details: 单块默认最多尝试 6 次、指数退避上限 2000ms，失败时间预算 chunkRetryBudgetMs（默认 60s）只计入故障开销，不误伤正常慢传。
  - title: 跨源续传与逐块校验
    details: 通过 .part 与 anabranch-meta/2 控制文件记录 P/D/V 状态，同源核对 ETag，落盘前验证逐块与整份 SHA-256。
  - title: 纯 Java 8 / 许可证隔离
    details: 核心仅 19 个源文件且严格通过 javac --release 8 与边界检查；可选原生 aria2 后端独立为 GPL-3.0-or-later AAR，互不牵连。
---

## 按身份 / 目标快速入口

| 你的身份与目标 | 推荐入口 | 解决什么问题 |
| --- | --- | --- |
| **应用开发者（JVM / Android）** | [定位、双产物与快速开始](/docs/) | 区分 `anabranch-core` 与 `anabranch-aria2`，跑通最小依赖与 `tools/run-checks.sh` |
| **构建与运维集成者** | [集成配置、原生构建与排错](/docs/deployment) | 核对参数默认值、Android `minSdk 21` 无 desugaring 约束、aria2 `.so` 构建与常见错误 |
| **扩展引擎与策略定制者** | [公开 API 与扩展契约](/docs/contracts) | 实现自定义 `Engine`（如 OkHttp）、`Policy`、`Source.Resolver` 或评估 `Aria2Downloader` 语义差异 |
| **深入核心协议细节** | [调度熔断、续传与校验机制](/docs/mechanisms) | 查看 EMA 公式、`.meta` v2 格式、先 `force` 后 `renameTo` 顺序与 dead-man's switch |
| **源码贡献与架构评审者** | [架构全景与一次真实运行](/docs/architecture) · [源码地图](/docs/code-map) | 跟踪端到端调用链路、定位 19 个核心类与 5 套测试，查看 [设计决策与发布迁移](/docs/decisions) |

## 最小调用示例

```java
Downloader dl = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .chunkRetryBudgetMs(60_000)
    .build();

SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("cdn-a", "https://cdn-a.example.test/pkg/"),
    Source.ofBase("cdn-b", "https://cdn-b.example.test/pkg/")),
    Policy.fastestFirst());

Result res = dl.download(
    new Request("app.bin", new File("out/app.bin"), integrity, 8, true),
    sources, Listener.NONE, Cancellation.NONE);
```
