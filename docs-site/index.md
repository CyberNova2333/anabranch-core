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
      text: API 与组件参考
      link: /api-reference/
    - theme: alt
      text: 公开 API 与契约
      link: /docs/contracts
    - theme: alt
      text: 架构与源码导览
      link: /docs/architecture

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

## 能力定位与明确边界

::: info 能做到什么程度（已实现能力）
- **零依赖纯 Java 8 多源并发**：仅 19 个 `Apache-2.0` 源文件（零 `android.*` 与三方库，兼容 `minSdk 21` 免 desugaring），支持 `Policy.fastestFirst()`（EMA 吞吐/并发）与 `weighted()` 并发调度及 `.part` + `anabranch-meta/2` 跨源断点续传。
- **失败预算熔断与强摘要检验**：默认单块 6 次退避与 `chunkRetryBudgetMs`（`60s` 纯失败计时），逐块与整份 SHA-256 检验后原子重命名；支持插拔自定义 `Engine`（如 OkHttp）、`Policy` 与 `Source.Resolver`。
- **可选 `aria2` 原生后端隔离**：通过独立 `GPL-3.0-or-later` `anabranch-aria2` AAR 无缝切换 `libaria2`，不污染纯 Java 调用方许可证与体积。
:::

::: warning 明确不能做什么（非目标与硬限制）
- **严格无状态下载库**：不内置镜像 URL 清单、不持久化全局配置、不承担压缩包解压或 APK 安装、不构建 UI 或 Android 前台服务。
- **已知契约与发版约束**：`Source.maxChunks` 目前仅作元数据尚未做单源并发硬限；`Aria2Downloader` 全局 `BUSY` 串行且不走 Core `Policy`；Maven Central 为手动发版（集成前须先确认在线可用或直接编源码）。
:::

## 架构全景

<DiagramFrame
  src="/diagrams/anabranch-architecture.html"
  title="多源分块调度与状态持久化架构（基于 Archify 的交互式拓扑，支持平移/缩放/探查）"
  aspect="710 / 448"
  new-tab-label="在新窗口全屏打开 ↗"
/>

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

## 文档分层导航

::: tip 按照读者需求快速跳转
- **应用开发者（JVM / Android）**：阅读 [定位、双产物与快速开始](/docs/)，区分 `anabranch-core` 与 `anabranch-aria2`，跑通最小依赖与 `tools/run-checks.sh`；
- **构建与运维集成者**：查阅 [集成配置、原生构建与排错](/docs/deployment)，核对参数默认值、Android `minSdk 21` 无 desugaring 约束、aria2 `.so` 构建与常见错误；
- **扩展引擎与策略定制者**：查阅 [公开 API 与扩展契约](/docs/contracts) 与 [调度熔断、续传与校验机制](/docs/mechanisms)，实现自定义 `Engine`（如 OkHttp）、`Policy`、`Source.Resolver`；
- **源码贡献与架构评审者**：查阅 [架构全景与一次真实运行](/docs/architecture)、[源码地图与目录索引](/docs/code-map) 与 [设计决策、演进与发布迁移](/docs/decisions)。
:::
