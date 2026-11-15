# Anabranch Core：源码阅读指南

把同一个文件拆为可调度的分块，同时向多条镜像源请求字节区间，在失败时按策略原地重试或切换备源，并在全部校验通过后才替换落盘成品。核心模块使用纯 Java 8 编写且零第三方依赖，可直接在桌面 JVM 与低版本安卓环境中运行。

本指南采用 Standard 模式，完整覆盖纯 Java 下载器的请求调度、并发传输、断点续传、校验提交及源码护栏，同时覆盖相邻原生下载后端模块的 Java 胶水接口、双后端故障切换协议与测试验证入口。仓库构建与发布流水线由独立发布手册承接，不在源码导读中重复展开。

## 阅读路径

| 读者目标 | 从这里开始 | 读完后获得什么 |
| --- | --- | --- |
| 第一次理解主流程 | [沿一次真实运行走到底](walkthroughs/one-real-run.md) | 掌握请求入口、探测、分块拉取、校验与成品提交的交接顺序 |
| 准备修改或定位模块 | [按职责寻找改动入口](code-map.md) | 快速定位核心包、测试套件、检查工具与原生后端源码位置 |
| 深入选源与熔断策略 | [多源调度与故障转移](modules/scheduling-and-failover.md) | 看懂速度平滑、并发让渡、失败预算与三连失败拉黑规则 |
| 深入续传与校验边界 | [断点续传与逐块校验](modules/resume-and-integrity.md) | 理解伴生元文件格式、跨源校验契约与落盘提交前提 |
| 接入自定义传输或原生后端 | [引擎接口与 aria2 边界](modules/engine-and-aria2-boundary.md) | 把握纯 Java 四条边界、原生切换开关与许可隔离 |
| 统一项目专有术语 | [查看核心术语表](glossary.md) | 对齐策略、元文件标记、滑窗测速与死手开关名词含义 |
| 核查证据与校验范围 | [源码证据审计](references/source-evidence.md) | 区分源码已确认事实、实际执行过的测试与未运行环境 |
| 评估阅读体验与风险 | [阅读质量审计](references/quality-review.md) | 了解读者视角验收结论、可证伪条件与剩余风险 |
| 跟踪指南同步基线 | [指南变更记录](change-log.md) | 查看文档重置摘要与最新源码提交锚点 |

## 运行与验证

日常校验直接依赖 JDK（支持 `--release 8` 编译）、Python 3 与 POSIX shell，不需要安装 Gradle 或提交 wrapper：

```sh
sh tools/run-checks.sh
```

脚本按 Java 8 字节码与标准库边界分别编译核心层和原生后端 Java 骨架，执行核心边界静态扫描，随后运行基于本机真实 HTTP 服务器的四套回归测试。如需验证原生 `libaria2` 的桌面宿主下载链路，另需具备 C/C++ 编译工具链与宿主依赖后运行：

```sh
sh aria2/native/run-host-tests.sh
```

## 文档站

仓库静态文档站位于独立目录中，使用 VitePress 构建并通过 `@include` 与路径重写插件直接复用本目录的运行走读和代码地图，确保同一套源码导航不在两处重复维护：

```sh
pnpm --dir docs-site install --frozen-lockfile
pnpm --dir docs-site run docs:dev -- --host 127.0.0.1
pnpm --dir docs-site run docs:build
```

证据状态：除特别标注外，本页基于当前源码已确认。
