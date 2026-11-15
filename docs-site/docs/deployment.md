# 集成配置、原生构建与排错

本页汇集 `anabranch-core` 与可选 `anabranch-aria2` 在 JVM、Android `minSdk 21` 及宿主测试环境下的配置矩阵、原生构建边界和常见故障排查路径。

## 配置参数矩阵

### `Downloader.Builder`

| 方法 | 默认值 | 说明与边界约束 |
| --- | --- | --- |
| `engine(Engine e)` | `new HttpEngine()` | 替换为自定义传输后端；不可为 `null` |
| `defaultChunks(int n)` | `8` | 最小为 `1`；当 `Request.chunks <= 0` 且无 `Integrity` 块列表时生效 |
| `maxConcurrent(int n)` | `4` | 最小为 `1`；同时执行的分块线程池上限 |
| `chunkRetryBudgetMs(long ms)` | `60_000` | 单块累计**失败与退避**时间预算；`<= 0` 表示不限；不扣除正常传输耗时 |
| `bytesPerSecond(long n)` | `0`（不限） | 整次下载写入侧令牌桶上限；`<= 0` 关闭限速 |
| `logger(Logger l)` | `Logger.NONE` | 诊断日志回调，核心不绑定任何日志框架 |

### `Request` 与 `HttpEngine`

- `Request(String key, File target, Integrity integrity, int chunks, boolean allowResume)`：若仅做快速原型测试可用 `Request.of(key, target)`（等价于 `integrity = null, chunks = 0, allowResume = true`）。
- `HttpEngine(int connectTimeoutMs, int readTimeoutMs, String userAgent, Logger log)`：无参默认配置为连接超时 `15_000` ms、读取超时 `30_000` ms、`User-Agent` 为 `"anabranch/0.1"`，并强制请求头 `Accept-Encoding: identity`。

### `Aria2Downloader.Builder`（独立 AAR）

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `stateDir(File dir)` | **必填（不可为空）** | 持久化 `aria2.backend` 与 `aria2.armed` 切换状态 |
| `selector(BackendSelector s)` | `FileBackendSelector(stateDir, "aria2-openssl", "aria2-gnutls")` | 控制加载失败即时换源与崩溃跨启动切换 |
| `logger(Logger l)` | `Logger.NONE` | 记录 `Source.weight` 被忽略等运行提示 |

## Android `minSdk 21` 集成约定

1. **无需 Core Library Desugaring**：`tools/run-checks.sh` 与 `build.gradle.kts` 均以 `--release 8` 编译 core 与 `aria2` 的 Java 层；核心与 `FileBackendSelector` 均避开 `java.nio.file` 与 `java.util.function` 等 API 24+/26+ 才提供的标准类库。
2. **网络权限与明文策略**：核心走标准 `HttpURLConnection`，在 Android 上需申请 `android.permission.INTERNET`；若测试镜像走明文 HTTP，需在应用的 `networkSecurityConfig` 中放行。
3. **存储权限与私有目录**：推荐将 `Request.target` 落在应用私有目录（`.part` 与 `.meta` 会同目录写入，无需额外权限）。核心**不会**替应用安装 APK、解压包体、弹出通知或请求后台保活。
4. **本地测试提前发现不同步镜像**：在多源下载启动前，可先调用 `downloader.probeAll(key, sources, cancel)` 检查 `ProbeReport.sizesAgree` 与 `etagsAgree`，尽早拦截返回了旧版尺寸的服务端。

## 可选 `aria2` 原生后端构建与宿主验证

原生胶水（`aria2/native/`）基于 NDK r25c 交叉编译 `aria2 1.37.0` 及 8 个固定 sha256 的 C/C++ 依赖，提供桌面宿主实测与 CI AAR 组装两条路径：

```sh
# 桌面宿主实测：编译宿主机 libaria2 .so 并跑 Aria2DownloaderTest（28 项真实 HTTP 用例）
sh aria2/native/run-host-tests.sh

# 裁剪版构建（去掉 libxml2 / SQLite / libssh2，显著缩小依赖面与体积）
ARIA2_SLIM=1 sh aria2/native/run-host-tests.sh
```

- Android 全 ABI 交叉编译与 AAR 打包定义在 [`.github/workflows/build-aria2.yml`](https://github.com/CyberNova2333/anabranch-core/blob/main/.github/workflows/build-aria2.yml)（手动 `workflow_dispatch` 触发，包含 16K 页对齐检查、armv7 对 API 21 stub 的 `UND` 符号差集检查与 [`aria2/THIRD-PARTY-NOTICES.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/aria2/THIRD-PARTY-NOTICES.md) 随包打包）。
- 切勿在同一进程内同时加载 `libaria2-openssl.so` 与 `libaria2-gnutls.so`：两者导出同名 `ANABRANCH_ARIA2_1.0` JNI 符号，切换通过 `FileBackendSelector` 落盘后在**下次进程启动**生效。

## 部署与运行排错 FAQ

### 1. 为什么在本地环境跑 `gradle build` 找不到 `./gradlew`？
仓库刻意不提交 Gradle Wrapper 二进制 jar；日常开发与 CI（`.github/workflows/checks.yml`）直接跑 `sh tools/run-checks.sh`。本机仅在需要打包 Maven Central 上传产物（`gradle centralBundle`）时才要求自行安装 Gradle 8+。

### 2. 下载失败抛出包含“服务端忽略 Range 请求”的异常是怎么回事？
当分块起点 `start > 0` 而镜像服务端无视 `Range` 头返回 HTTP `200` 全量体时，`HttpEngine` 会立即抛出 `IOException` 拒绝继续写入，避免把文件头写进中间偏移造成静默损坏。对于完全不支持 `Range` 的服务端，`Downloader` 只会在探测首轮（`probe.rangeSupported == false`）自动退回整份单流下载。

### 3. 下载中途失败后目录里留着 `.part` 和 `.meta`，是泄漏吗？
当 `allowResume == true` 且服务端支持 `Range` 时，失败保留 `.part` 与 `.meta` 是为了下次调用自动断点续传；当 `allowResume == false` 或服务端不支持 `Range` 时，`Downloader` 会在 `catch` 分支主动清理这两份临时文件。若调用方需清理放弃续传的任务，手动删除目标同名的 `.part`/`.meta` 即可。

### 4. `aria2` 在加载阶段报 `UnsatisfiedLinkError` 怎么处理？
`Aria2Downloader` 在加载当前首选后端失败时会调用 `selector.reportLoadFailure(backend)` 立即在同一次下载尝试切换到备选 TLS 后端（`aria2-openssl` ↔ `aria2-gnutls`）；若两个后端均无法加载，才把异常抛回调用方。若是在运行期间发生 native `SIGSEGV`/`SIGABRT`，已通过 `fsync` 落盘的 `aria2.armed` 标记会在下次进程启动时自动令 `selector.current()` 翻转至另一后端。

### 5. `MultiSourceTest` 在本机高并发或特殊环境下报探头 `Read timed out`？
`testStalledSourceIsBoundedByBudget` 刻意为模拟节点 `a` 注入 `4000ms` 延迟并搭配 `1500ms` 读取超时；如果首轮探测请求本身因超时未能在 `probeFirst` 拿到头信息，任务会直接报所有源探测失败而不是进入分块预算测试。请保证运行环境网络回环无外部干扰后复测，或对照源码排查具体断言日志。
