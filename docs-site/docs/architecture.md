---
repoGuideSource: repo-docs/walkthroughs/one-real-run.md
---

# 架构全景与一次真实运行

Anabranch Core 采用严格的单向分层架构：上层编排与状态持久化通过接口驱动下层纯传输引擎，绝不允许引擎反向依赖调度状态；可选原生 `aria2` 后端作为独立制品复用请求、回调与源集合模型，但不进核心包。

```text
  调用方 (JVM / Android minSdk 21)
     │
     ├─► 纯 Java 路径 (io.anabranch.*) ───────────────────────────────────────┐
     │     Downloader ─┬─► SourceSet + Policy + Stats (EMA 选源 / 熔断 / 退避) │
     │                 ├─► ResumeState (.part / .meta v2) + Integrity (SHA-256)│
     │                 └─► Engine (HttpEngine) ──► 闭区间 Range 写入 + RateLimiter
     │                                                                        │
     └─► 可选原生路径 (io.anabranch.aria2.*，独立 GPL-3.0-or-later AAR)       │
           Aria2Downloader ─► FileBackendSelector (dead-man's switch)         │
                           └─► Aria2Native (runOnce) ──► libaria2 .so ────────┘
```

## 交互式历史图谱资产说明

下面嵌入仓库既有交互页面 [`/diagrams/anabranch-architecture.html`](/diagrams/anabranch-architecture.html)（随站保留的静态预览资产）。**请注意甄别**：该图实际描绘的是仓储运维辅助脚本（多远端镜像同步）而非上述 Java 下载库本身；要理解 Java 下载核心的真实结构与一次运行链路，请以上方分层图及下方《一次真实运行》源码走读为准。

<div style="margin: 16px 0; border: 1px solid var(--vp-c-divider); border-radius: 12px; overflow: hidden;">
  <iframe src="/diagrams/anabranch-architecture.html" style="width: 100%; height: 520px; border: 0;" title="仓库保留历史交互架构图"></iframe>
</div>

## 一次真实运行全流程（同步自 `repo-docs/`）

以下流程直接引用自仓库唯一源码指南 [`repo-docs/walkthroughs/one-real-run.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/repo-docs/walkthroughs/one-real-run.md)，站内仓库路径自动解析为对应源码定位标注：

<!--@include: ../../repo-docs/walkthroughs/one-real-run.md{2,}-->
