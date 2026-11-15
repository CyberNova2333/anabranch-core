# 源码证据审计

本页记录基于 `8c03cd4f5621acd4e7cbcf02cbcd4f85a61efb4f` 的源码核对证据；行为演练由[一次真实运行](../walkthroughs/one-real-run.md)承载，源码分布由[代码地图](../code-map.md)承载。

## 遍历记录

| 遍历 | 实际检查 | 得到的依据 |
| --- | --- | --- |
| Pass 1 | 全仓目录树、`git rev-parse HEAD`、`build.gradle.kts`、`settings.gradle.kts`、`.github/workflows/*`、`docs/{ARIA2,DESIGN,RELEASING}.md`、`CONTRIBUTING.md`、`tools/*` | 确立 Java 8 / minSdk 21 核心定位、Gradle 仅作发布、保留 `docs/RELEASING.md` 与 `docs/ARIA2.md` 引用边界，识别原 `DESIGN.md` 草案与实际代码的差异 |
| Pass 2 | `src/io/anabranch/*.java` 全部 19 个源文件、`aria2/src/io/anabranch/aria2/*.java` 全部 4 个源文件、`aria2/native/*`、`test/*.java` 与 `aria2/test/*.java` | 确认调度选源、EMA 平滑、失败退避与重试预算、`.meta` v2 格式、`Integrity` 提交顺序、核心 4 条边界规则及 `Aria2Downloader` 单线程 `runOnce` 与 dead-man's switch |

## 覆盖与排除

覆盖：`src/io/anabranch/` 全部 19 个核心源文件、`test/` 3 套真实 HTTP 测试、`aria2/` Java 胶水层与 JNI 符号/开关协议、`tools/` 边界扫描与提交校验工具、`build.gradle.kts` 及 CI 流水线职责划分。
排除：不在本地构建 Android NDK 交叉编译 `.so`（无 NDK 与网络下载 tarballs）及真机 Android instrumented 环境；不虚构未执行的 `gradle` 与宿主 `run-host-tests.sh` 运行通过结论。

## 声明与证据

| Claim | Evidence | Confidence | Caveat | Used by |
| --- | --- | --- | --- | --- |
| 核心 19 个文件零三方依赖且兼容 API 21 | [边界检查脚本](../../tools/check-core-boundaries.py)、[检查脚本](../../tools/run-checks.sh) `--release 8` | 已确认 | 需配合调用方环境权限使用 | 总览、模块 3 |
| 选源按 EMA 吞吐与在途并发比值决策，`Source.maxChunks` 暂未限流 | [Policy](../../src/io/anabranch/Policy.java)、[SourceSet](../../src/io/anabranch/SourceSet.java)、[Source](../../src/io/anabranch/Source.java) | 已确认 | 无块摘要时不证明镜像内容一致 | 模块 1、主流程 Step 3 |
| 失败预算只计失败耗时，单块最多 6 次且退避上限 2000ms | [Downloader](../../src/io/anabranch/Downloader.java) `fetchOneChunk` | 已确认 | 探测阶段全源超时会直接报错，不进分块预算循环 | 模块 1 |
| `.meta` v2 同源比 ETag，跨源只比总长与 `unit`，校验不通过不重命名 | [ResumeState](../../src/io/anabranch/ResumeState.java)、[Downloader](../../src/io/anabranch/Downloader.java) `commit` | 已确认 | `target.delete()` 后 `renameTo` 不保证跨文件系统原子性 | 主流程 Step 2–4、模块 2 |
| `Aria2Engine` 已删除，独立 AAR 采用 `Aria2Downloader` 全局串行 + 双 TLS 开关 | [Aria2Downloader](../../aria2/src/io/anabranch/aria2/Aria2Downloader.java)、[FileBackendSelector](../../aria2/src/io/anabranch/aria2/FileBackendSelector.java)、[aria2 说明](../../docs/ARIA2.md) | 已确认 | 两份 `.so` 同进程不能混载，切换在下轮生效 | 模块 3 |
| `sh tools/run-checks.sh` 在当前环境运行部分暴露既有探头超时用例失败 | 实际运行输出（`SingleSourceTest` 21/0，`MultiSourceTest` 31/2） | 已确认 | `testStalledSourceIsBoundedByBudget` 在探测阶段超时中止，后续 `ThrottleTest` 与 `Aria2FailoverTest` 未触发执行 | 主流程 Step 5、总览 |

## 证伪检查与读者下一问

证伪检查：若任意修改后坏块被标为 `'V'`、非同源续传因 `ETag` 不等直接作废、或 `Engine` 反向导入调度层类库，本指南即失效；需立即复查源码并重新运行 `sh tools/run-checks.sh`。

读者下一问：如何实际交叉编译 Android ABI `.so` 并打包带 GPG 签名的 Maven Central Bundle 与对应 GPL 源码压缩包？请追踪仓库根目录的 `docs/RELEASING.md`、`docs/ARIA2.md`、`aria2/THIRD-PARTY-NOTICES.md` 与 `.github/workflows/build-aria2.yml`。

## 验证边界

本轮在当前 Linux 工作区（OpenJDK 25.0.5、`pnpm 12.6.0`、`python3 3.15.2`，未安装本机 `gradle`）已实际核对：
- 运行 `sh tools/run-checks.sh`：core 与 aria2 Java 8 编译通过，`check-core-boundaries.py` 通过（19 个文件），`SingleSourceTest` 通过 21 / 失败 0，`MultiSourceTest` 通过 31 / 失败 2（`testStalledSourceIsBoundedByBudget` 中源 `a` 的 `4000ms` 延迟导致在 1500ms 读超时下于探测阶段抛 `Read timed out`），脚本随后按 `set -e` 退出 1，未继续执行 `ThrottleTest` 与 `Aria2FailoverTest`。
- 本地环境未运行 `sh aria2/native/run-host-tests.sh`、未执行 `gradle` 或 Android 真机测试；所有源码相对路径链接已在工作树逐项核对，文档站构建阶段通过 VitePress `guideLinks` 将仓库源码链接渲染为纯路径标注，避免站外死链。

证据状态：除特别标注外，本页基于当前源码已确认。
