# 指南变更记录

## 2026-11-15：升级为 Standard 模式并重置全仓文档体系

- 模式升级：将 `repo-docs/` 从 Lite 扩展为 Standard 模式，新增 `modules/scheduling-and-failover.md`、`modules/resume-and-integrity.md`、`modules/engine-and-aria2-boundary.md` 与 `glossary.md`，补全中层行为机制、常量契约与边界示例。
- 事实校准：按源码重写主流程演练与代码地图，核实 `Downloader.Builder` 参数、`Source.maxChunks` 现状、`.meta` v2 协议、`Aria2Downloader` 串行与 dead-man's switch 机制，并清理 `docs-site` 中全部与仓库无关的样板幻觉文案。
- 载体协同：`docs-site` 采用 VitePress `guideLinks` 与 `@include` 直接复用本目录的运行走读与代码地图；同时迁移并移除过时草案 `docs/DESIGN.md`，保留 `docs/RELEASING.md` 与 `docs/ARIA2.md`。
- 验证记录：实测 `sh tools/run-checks.sh` 在 Java 8 编译、`check-core-boundaries.py`（19 文件）与 `SingleSourceTest`（21/0）通过，在 `MultiSourceTest` 暴露 `31/2`（`testStalledSourceIsBoundedByBudget` 探头读超时）；同步执行 `validate_repo_docs.py` 与 `pnpm --dir docs-site run docs:build`。

Synced through 8c03cd4f5621acd4e7cbcf02cbcd4f85a61efb4f

## 2026-10-29：初建指南并统一 VitePress 入口

- 建立 `repo-docs/` Lite 指南、中英 README 路由表与 VitePress 站内集成，将源码验证命令与代码地图入口固定至仓库脚本与核查基线。
