# 定位、双产物与快速开始

Anabranch Core 解决的是单文件、多镜像并发下载与可信校验问题。与常见只面向单 URL 多线程拉取的下载库不同，它按固定分块为单元调度多条镜像：先探头确认长度与 `Range` 能力，再在传输失败或坏块验签失败时切源重试，并在重命名落盘前完成 SHA-256 核验。

## 核心能力对比

| 能力维度 | Android 系统 `DownloadManager` | 常规单源分段下载库 | `Anabranch Core` |
| --- | --- | --- | --- |
| 同文件多镜像并发拉取 | 不支持 | 不支持 | 支持（`SourceSet` + `Policy`） |
| 按分块失败切源与熔断 | 不支持 | 不支持 | 支持（`RETRY_SAME` / `SWITCH_SOURCE` / 拉黑） |
| 同源 `ETag` 与跨源摘要续传 | 仅黑盒内部处理 | 多为单 URL 断点 | `anabranch-meta/2` + `Integrity` 逐块/整份校验 |
| 零三方依赖 / `minSdk 21` 兼容 | 系统内置 | 通常带反射/线程库依赖 | 19 个纯 Java 8 文件，无需 desugaring |
| 可选原生大吞吐后端 | 无 | 无 | 独立 `anabranch-aria2` AAR（libaria2 JNI） |

## 双产物隔离结构

为保证绝大多数调用方零 native 体积、零 GPL 传染，仓库将下载实现严格拆分为两个产物：

| Maven 构件（`build.gradle.kts`） | 包名 | 实现方式 | 运行时依赖与许可证 |
| --- | --- | --- | --- |
| `io.github.cybernova2333:anabranch-core:0.1.0` | `io.anabranch.*` | 纯 Java 8（`HttpURLConnection`） | **零第三方依赖**，不引入 `android.*`，`AGPL-3.0-only`（适用根目录 `LICENSE-ADDITIONAL_TERMS-zh-CN.md` / `ASSETS_LICENSES.md`） |
| `io.github.cybernova2333:anabranch-aria2:0.1.0`（可选） | `io.anabranch.aria2.*` | Java 胶水 + JNI `libaria2` `.so` | 依赖同版本 core；二进制 AAR 按 `GPL-3.0-or-later` 分发 |

> **发布状态提醒**：`build.gradle.kts` 已声明上述坐标与签名打包任务，但制品**尚未自动推至 Maven Central**（发布为手动动作）。集成前请先核对 Central 上是否已有目标版本；如从源码直连，可直接引用 `src/io/anabranch/` 或在本地执行 `gradle centralBundle` 检验构建产物。
>
> ## 能做到什么程度与明确不能做什么
>
> - **能做到什么程度**：纯 Java 8（19 源文件、AGPL-3.0-only、Android `minSdk 21` 免 desugaring）完成多源分块竞速、失败切换、`.part` + `anabranch-meta/2` 跨源续传与 SHA-256 逐块核验，并通过独立 `anabranch-aria2` AAR 隔离开 GPL 原生加速与自定义 `Engine`/`Policy`/`Source.Resolver`。
> - **明确不能做什么**：刻意保持无状态下载库身份——不内置镜像清单、不存全局设置、不解包压缩包、不装 APK、无 UI 与前台服务；`Source.maxChunks` 尚仅作元数据；`Aria2Downloader` 全局 `BUSY` 串行执行且下载完成后统一校验 `.a2part`。
>
> **交互式接口参考**：需按类查阅 `Downloader`、`Request`、`Integrity`、`Engine`/`Policy` 与 `Aria2Downloader` 签名和限制，可直接使用 [API 与组件参考](/api-reference/)。

## 一分钟源码编测（零 Gradle 依赖）

日常开发与基线验证不需要安装 Gradle 或提交 `gradlew`——仓库只依赖 JDK（支持 `javac --release 8`）、Python 3 与 POSIX shell：

```sh
sh tools/run-checks.sh
```

脚本会依次执行：
1. 以 `javac --release 8` 分别编译 `src/io/anabranch/*.java` 与 `aria2/src/io/anabranch/aria2/*.java`（直接在类库级别封堵 Java 9+ 与 Android API 24+ 误用）。
2. 执行 `python3 tools/check-core-boundaries.py`，检查 19 个核心源文件是否零 `android.*`、零外部依赖、零反向引擎依赖。
3. 启动本地真实 HTTP 测试服务器依次运行 `SingleSourceTest`、`MultiSourceTest`、`ThrottleTest` 与 `Aria2FailoverTest`。

## 最小接入示例（纯 Java / Android）

```java
Downloader downloader = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .bytesPerSecond(0) // <= 0 不限速
    .build();

SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("cdn-a", "https://cdn-a.example.test/releases/"),
    Source.ofBase("cdn-b", "https://cdn-b.example.test/releases/")),
    Policy.fastestFirst());

Result result = downloader.download(
    new Request("sdk.zip", new File(targetDir, "sdk.zip"), integrity, 8, true),
    sources,
    Listener.NONE,
    Cancellation.NONE);
```

接下来请继续阅读 [集成配置、原生构建与排错](/docs/deployment)，核对全部可调参数、Android 权限及可选 `aria2` 后端构建流程。
