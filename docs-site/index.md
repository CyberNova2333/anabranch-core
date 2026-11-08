---
layout: home
repoGuideSource: "repo-docs/README.md"

hero:
  name: Anabranch Core
  text: 纯 Java 高性能分块下载与多源调度引擎
  tagline: 文件拆块调度、多镜像源自适应取流、失败隔离重试与端到端哈希校验，与 Android/Native 干净解耦。
  actions:
    - theme: brand
      text: 开启文档阅读
      link: /walkthroughs/one-real-run
    - theme: alt
      text: 架构代码地图
      link: /code-map
    - theme: alt
      text: GitHub 仓库
      link: https://github.com/CyberNova2333/anabranch-core

features:
  - title: 纯 Java 零重型框架依赖
    details: 面向 Java 17/21 现代运行时打造，核心逻辑无需任何第三方重量级容器或特定平台 SDK，原生跨平台。
  - title: 智能多源动态调度
    details: 支持为单个目标文件配置多条上游 URL，自适应探测各源响应速度与 Range 支持，实现块级并行下载。
  - title: 严格断点与校验落盘
    details: 块级临时状态持久化保存，断点续传即时恢复，最终成品在组装完成后进行全量哈希校验方准提交。
---

## 这是什么

**Anabranch Core** 是一套专注于大文件可靠传输的高性能分块下载调度引擎。

在不稳定网络或多镜像源并发场景下，单连接下载极易中断甚至损坏文件。Anabranch 将目标文件拆解为受调度的逻辑分块，支持多条镜像源同时拉取；每一块独立重试、独立校验，最终在内存或临时卷中按序号原子组装，确保提交给上层的每一个成品文件均符合权威摘要。


## 架构全景

<div class="archify-diagram-card" style="margin: 1.5rem 0; border: 1px solid var(--vp-c-border); border-radius: 12px; overflow: hidden; background: var(--vp-c-bg-soft); box-shadow: 0 4px 16px rgba(0,0,0,0.06);">
  <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 18px; background: var(--vp-c-bg-elv); border-bottom: 1px solid var(--vp-c-border); font-size: 13px;">
    <span style="font-weight: 600; color: var(--vp-c-text-1); display: flex; align-items: center; gap: 8px;">
      <span style="font-size: 16px;">📐</span> 多端镜像同步引擎架构（交互式架构图表，支持平移 / 缩放 / 探查）
    </span>
    <a href="/diagrams/anabranch-architecture.html" target="_blank" rel="noreferrer" style="font-size: 12px; color: var(--vp-c-brand-1); text-decoration: none; font-weight: 500;">
      在新窗口全屏打开 ↗
    </a>
  </div>
  <iframe src="/diagrams/anabranch-architecture.html" width="100%" height="520px" frameborder="0" style="display: block; border: none;" allowfullscreen loading="lazy"></iframe>
</div>

## 怎么用 / 快速上手

### 1. 运行环境与验证

准备 JDK 17 或 21、Python 3 环境：

```bash
# 运行核心单元测试套件
python scripts/test.py

# 执行一次端到端真实分块调度流程测试
python scripts/test-e2e.py
```

### 2. Java 核心 API 调用示例

```java
import me.cbnv.anabranch.core.Downloader;
import me.cbnv.anabranch.core.DownloadTask;

Downloader downloader = Downloader.createDefault();
DownloadTask task = downloader.newTask()
    .addSource("https://mirror1.example.com/asset.pak")
    .addSource("https://mirror2.example.com/asset.pak")
    .destination(Path.of("downloads/asset.pak"))
    .expectedSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    .build();

task.start().join(); // 阻塞或注册监听器等待完成
```

## 分层阅读与快速跳转

::: tip 按读者角色快速检索
- **初次接触 / 快速理解**：从[一条真实主流程](/walkthroughs/one-real-run)开始，跟随一次大文件下载请求从 URL 探测、拆块到组装落盘的全过程。
- **架构设计 / 模块定位**：从[代码地图](/code-map)进入，查阅调度器（Scheduler）、源选择器（SourceSelector）与成品校验提交器（Committer）设计。
- **审计复核 / 核心贡献**：从[源码证据](/references/source-evidence)核对并发断言与异常边界，查阅[阅读质量审计](/references/quality-review)了解边界保障。
:::

---

## 完整项目指南概览

<!--@include: ../repo-docs/README.md-->
