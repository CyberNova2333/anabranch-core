# 阅读质量审计

本页记录从首次接入开发者与源码维护者视角对当前指南结构的验收检查，用于确认关键边界是否讲透以及尚余哪些验证盲区。

## 读者模拟

| 检查问题 | 从指南得到的答案 |
| --- | --- |
| 主路径是什么 | 纯 Java 8 下载器从 `Request` 与 `SourceSet` 出发，探测首源并打开 `.meta` v2 续传，并发拉取分块并按 `Integrity` 校验后重命名提交 |
| 难点是什么 | 传输成功不等于内容可信；必须隔离同源 `ETag` 与跨源校验职责，同时控制失败退避预算与 `Engine` 单向依赖边界 |
| 阶段如何衔接 | 调用请求 → `probe` 与 `ResumeState.open` → `Policy.pick` + `Engine.fetchRange` + 逐块 SHA-256 → `force` + `wholeSha256` + 删改提交 |
| 失败和边界在哪里 | 无摘要时不防镜像漂移；无 Range 失败会删临时文件；`commit` 先删后改名非原子事务；`aria2` 串行且忽略 `Policy`/`Stats` |
| 如何验证理解 | 提供 `sh tools/run-checks.sh`（真实本机 HTTP 测试，并如实记录本次 `MultiSourceTest` 31/2 运行情况）与 `sh aria2/native/run-host-tests.sh` |
| 从哪里改 | [代码地图](../code-map.md) 与三篇模块深潜页分别锁定核心调度、续传校验、引擎边界与 `aria2` 胶水的精确文件与符号 |

## 可证伪性与后续问题

证伪检查：若源码将 `Source.maxChunks` 接入硬限制、更改 `.meta` 头版本、恢复 `Engine` 形态的 `aria2` 实现或变更 `Downloader.Builder` 参数签名，对应模块指南与术语表必须同步更新。

读者下一问：怎样发布新版本到 Maven Central 以及怎样满足 `anabranch-aria2` AAR 的 `GPL-3.0-or-later` 源码提供义务？需跳转 `docs/RELEASING.md`、`docs/ARIA2.md` 与 `aria2/THIRD-PARTY-NOTICES.md` 按流程核对。

## 剩余风险

- 本地未安装 `gradle` 且未配置 NDK r25c / libaria2 编译环境，因此 `build.gradle.kts` 打包与 `aria2/native/` 交叉编译脚本仅作源码与 CI 配置级审计，未在本地执行。
- 本地运行 `sh tools/run-checks.sh` 时，`MultiSourceTest` 内的 `testStalledSourceIsBoundedByBudget` 因延迟源触发探测阶段 `Read timed out`（31/2），导致后续两套主类未在同一次运行中执行；修改代码前若需复核限速与 `Aria2FailoverTest` 可单独或排查后复测。

证据状态：除特别标注外，本页基于当前源码已确认。
