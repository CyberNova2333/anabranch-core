# 设计决策、演进与发布迁移

本页记录 Anabranch Core 从旧版内部下载模块净室重写并拆分为独立库时的核心架构取舍、`Aria2Engine` 实测淘汰结论、`.meta` 版本兼容性承诺，以及 Maven Central 发布入口。

## 核心架构与职责边界决策

### 1. 剥离应用耦合、坚持无状态库设计
库名取自「Anabranch（河流分出又汇回的支汊）」，核心设计准则是不把应用业务逻辑长进下载 API：
- **不存固定清单**：不内置资源表或槽位下标，只认调用方传入的单次 `Request`。
- **不内置 URL**：弃用早先只支持「固定基址 + 文件名」拼接的局限，抽象出 `Source.Resolver` 以兼容多云签名 URL、不同路径前缀与临时 Token。
- **零全局基础设施依赖**：不读 `SharedPreferences`、不写配置文件、不绑定日志框架（`Logger.NONE` / `Listener.NONE` / `Cancellation.NONE` 全为默认空实现）。
- **明确不做的五件事**：不做包安装或目录事务替换、不做通知/前台服务 UI、不做进程重启恢复编排、不内置地址表、不引入特定业务领域概念。

### 2. `Listener`、`Cancellation` 与单位统一
- 早先草案将进度观察与取消信号挤在单一接口内，迫使只监听进度的调用方也要实现 `isCancelled()`；正式核心将两者彻底拆分为 `Listener` 与 `Cancellation`。
- 速度回调 `Listener.onSpeed(double bytesPerSec)` 严格以**字节/秒**传递原始数值，拒绝库内擅自按 1000 或 1024 换算 Mbps/MiB 造成歧义。

### 3. 保留 `probeAll` 用于早发现镜像漂移
`Downloader.probeAll` 虽不自动阻塞普通 `download`，但特意保留为公开方法：多源下载最危险的隐患是某条备用镜像仍缓存旧版本、甚至尺寸碰巧相同但字节不同；正式下载前调用 `probeAll` 可低成本拦截已知的长度漂移镜像，余下的隐性差异则由 `Integrity` 逐块 SHA-256 兜底。

## 原生后端演化：`Aria2Engine` 的实测淘汰

最初设计曾设想通过 `Aria2Engine implements Engine` 接入 `libaria2`——让核心照旧划分块并传自定义 `Range` 头拉取单个区间。但 **2026-09-01 对 `aria2 1.37.0` 进行真 HTTP 服务器实测**证实该假设在工具语义上不可行：
- 当为单请求设置自定义 `Range: bytes=1000-1999` 时，aria2 虽能正确写完这 1000 字节，但会将响应 `Content-Range` 里的**文件总长**视为自身期望接收量，从而无限挂起等待剩余字节、既不报错也不退出。
- 依照「宁可明确失败切源、绝不留半活挂起路径」的原则，`Aria2Engine` 已从仓库彻底移除，唯一形态改为整文件多 URI 交付的 `Aria2Downloader`。
- 控制面也一并从早年常见的「argv 驱动主循环 + 本地 JSON-RPC 端口」修正为 `--enable-libaria2` 公开 C++ session API：无本地端口暴露、无 RPC secret 维护、Java 单线程通过 `runOnce` 与 `sessionShutdown` 驱动，彻底规避跨线程调用及参数错直接 `exit()` 杀进程的风险（详实实测复盘见仓库 [`docs/ARIA2.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/docs/ARIA2.md)）。

## 版本演进与 `.meta` 兼容承诺

- **`0.x.y` API 策略**：在 `0.1.0` 等 `0.x.y` 阶段不锁死 Java 公共 API 兼容性；若有接口微调会尽量集中在主要演进窗口完成。
- **`.meta` 格式单独承诺**：伴生控制文件头部的 `anabranch-meta/2` 版本格式具备独立兼容性边界——遇到不可识别版本的 `.meta` 一律安全作废并重新下载，绝不强行解读损坏续传状态；若将来必须破坏 `.meta` 格式兼容，版本号至少递增 minor 并在提交信息中说明。

## 源码验证基线与发布入口

- **当前验证基线**：代码已按照仓库代码审计与 `repo-docs/` 核对记录锚定至提交 `8c03cd4`。
- **正式发布手册**：仓库采用手动有意识发版（不设自动 push 发版触发器）。本地执行 `gradle build` / `gradle centralBundle` 打带 GPG `.asc` 签名的 Central Upload Bundle，或使用 GitHub Actions 手动运行 `build-core.yml` 并可选随包发布 `anabranch-aria2` AAR 及 GPL 对应源码压缩包。完整操作与秘密凭据清单请严格参阅 [`docs/RELEASING.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/docs/RELEASING.md) 与 [`aria2/THIRD-PARTY-NOTICES.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/aria2/THIRD-PARTY-NOTICES.md)。
