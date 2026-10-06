# Anabranch · 多源分块下载 🌊

> **面向 Java 与 Android 的多源下载库：分块调度、续传、校验与可替换引擎。**

[English](README.en.md) · [架构设计](docs/DESIGN.md) · [Apache-2.0](LICENSE)

同一个文件可以从多条线路按块获取；调度器依据实测吞吐分配任务，失败时重试或换源，校验通过后再提交成品。核心采用 Java 8 字节码、零第三方依赖，不引用 Android API；Android 兼容目标为 minSdk 21。

<a id="-架构与职责"></a>

**阅读导航**

[🏛️ 架构与职责](#section-1) · [🚀 快速开始](#section-2) · [⚙️ 使用边界](#section-3) · [🧪 验证与协作](#section-4) · [📜 许可](#section-5) · [⚙️ 集成与产物](#section-6)

---

<a id="section-1"></a>

## 🏛️ 架构与职责

```mermaid
flowchart LR
    Request[下载请求与源集合] --> Downloader[Downloader 分块与提交]
    Downloader --> Policy[Policy / Stats 选路]
    Downloader --> Engine[Engine 获取字节区间]
    Downloader --> Integrity[摘要校验与续传状态]
    Engine --> HTTP[HttpEngine]
```

| 模块 | 职责 |
|---|---|
| `src/io/anabranch/` | 下载调度、线路策略、摘要、续传、限速与测速 |
| `aria2/` | 独立的 native 下载后端；不打进 core JAR |
| `test/` | 单源、多源、限速及后端故障测试 |
| `tools/` | 编译、边界检查与发布辅助 |

<a id="-快速开始"></a>

<a id="section-2"></a>

## 🚀 快速开始

准备 JDK 17 或 21（与当前 CI 一致）、Python 3、Git 和 POSIX shell，在仓库根目录运行；core 编译仍以 `javac --release 8` 检查兼容性：

```sh
sh tools/run-checks.sh
```

脚本执行 Java 8 兼容编译、完整编译、core 边界检查和测试。下载测试使用本地 HTTP 服务传输真实字节；无需访问业务线路。

Maven Central **尚未发布**。预定坐标与发布步骤见 [发布说明](docs/RELEASING.md)，集成时先按 [设计文档](docs/DESIGN.md) 使用源码。

<a id="-使用边界"></a>

<a id="section-3"></a>

## ⚙️ 使用边界

- 同源续传比较 ETag；跨源以长度及内容摘要确认，不能用不同服务器的 ETag 判断是否为同一文件。
- 限速针对整次下载；策略层负责重试、切源和失败预算，引擎只负责取字节。
- 服务端不支持 Range 时退化为整份下载，中断后无法续传。
- aria2 后端的真机验证与独立 AAR 发布仍待完成，见 [aria2 接入说明](docs/ARIA2.md)。

<a id="-验证与协作"></a>

<a id="section-4"></a>

## 🧪 验证与协作

修改 native 后端时另运行 `sh aria2/native/run-host-tests.sh`，环境要求见其脚本与 aria2 文档。
贡献规则见 [CONTRIBUTING.md](CONTRIBUTING.md)、[AGENTS.md](AGENTS.md) 和 [CLAUDE.md](CLAUDE.md)。

<a id="-许可"></a>

<a id="section-5"></a>

## 📜 许可

core 使用 [Apache-2.0](LICENSE)。aria2 后端单独分发，其依赖许可与源码提供要求见 [aria2 文档](docs/ARIA2.md)。


<a id="section-6"></a>

## ⚙️ 集成与产物

[build.gradle.kts](build.gradle.kts) 定义 core 的 Java 库与发布元数据，[settings.gradle.kts](settings.gradle.kts) 定义工程名称。仓库没有 Gradle wrapper，需自行准备兼容 Gradle；首次验证优先使用上面的 `run-checks.sh`，它直接调用 JDK，无需先发布制品。

调用方提供下载请求、源列表和目标文件；先参考 [设计文档](docs/DESIGN.md) 与 [测试目录](test/) 的实际调用。下载成功、可恢复的中间文件和校验失败是不同结果，集成层不应在校验前把临时文件当作成品。
