# 多源分块下载框架 · API 草案

> 目标：把 legacy-client 里那套下载实现的**设计**做成一个与游戏无关的 Android 库。
> 代码基本要重写——这份文档定的是重写要顶住的那个面。
>
> 库名：**Anabranch**（河流分出去又汇回来的支汊）。包名 `io.anabranch`。

---

## 〇、这个库要解决什么（以及为什么现成的不够）

| 能力 | 系统 DownloadManager | FileDownloader / PRDownloader / Fetch2 | 本库 |
|---|---|---|---|
| 多线程分块 | ❌ | ✅ | ✅ |
| **同一文件多源并行** | ❌ | ❌ | ✅ |
| **按块失败切源** | ❌ | ❌ | ✅ |
| **源竞速与权重** | ❌ | ❌ | ✅ |
| 逐块校验 | ❌ | ❌ | ✅ |
| 可换下载引擎（含 native） | ❌ | ❌ | ✅ |

**差异点只有一个词：多源。** 多线程分块是成熟品类，不必再造；这个库的理由是
「一个文件、N 条线路、按块调度」。定位写清楚，别混成「又一个下载库」。

---

## 一、要甩掉的三层耦合（重写的真正原因）

原实现不是命名不好，是三样东西长进了 API：

1. **围绕一张固定包表建模。** `installOfflineNow(int index)`、`indexOfArchive(String)`、
   `HOT_SLOT_SCENARIO = slotOf("cn_scenario_update.zip")`——调用方要按下标说话。
   **新 API 只认「调用方给的一次请求」**，库内不存表。
2. **写死的锚点。** 引导地址硬编码在下载器里，代理配置要推进 native hook。
   **新 API 一个 URL 都不内置。**
3. **强绑基础设施。** 依赖同包的日志 / 路径 / 调试开关 / 崩溃留痕。
   **新 API 只暴露接口，默认实现全部 no-op。**

---

## 二、核心类型

### 2.1 源

```java
/** 一条线路。多个 Source 必须产出**逐字节相同**的内容——这是全库的前提。 */
public final class Source {
    public final String  id;          // 日志与统计用，不参与选路
    public final int     weight;      // 静态权重，>0
    public final int     maxChunks;   // 该源允许的并发分片数；<=0 用全局默认
    public final boolean enabled;

    /** 由调用方决定「逻辑文件 → 这条源上的 URL」。 */
    public interface Resolver { String urlFor(String key); }
}
```

> **和原实现的区别**：原来是 `base + name` 拼 URL，隐含「所有源同路径」。
> 改成 `Resolver` 之后，镜像路径不一致、需要签名 URL、CDN 带 token 的场景都能覆盖。
> 这是通用化里最值钱的一处改动。

```java
/** 一组源 + 选路策略。库持有观测数据，策略只读。 */
public final class SourceSet {
    public SourceSet(List<Source> sources, Policy policy);

    public interface Policy {
        /** 为下一个分片挑一条源。stats 是库维护的实时观测。 */
        Source pick(List<Source> candidates, Stats stats, ChunkRef chunk);
        /** 某源在某块上失败后的处置。 */
        Decision onFailure(Source s, ChunkRef c, Throwable e, int attempt);
    }
    public enum Decision { RETRY_SAME, SWITCH_SOURCE, DISABLE_SOURCE, ABORT }
}

/** 库维护、策略只读。吞吐用 EMA 而非历史峰值——峰值会被一次偶然的快传永久锁死。 */
public interface Stats {
    long  emaBytesPerSec(Source s);
    int   consecutiveFailures(Source s);
    int   inFlight(Source s);
    long  lastSuccessAtMillis(Source s);
}
```

内置策略：`Policy.weighted()`（静态权重）、`Policy.fastestFirst()`（EMA 竞速）、
`Policy.raceFirstChunk()`（首块并发抢跑、其余跟随胜者）。

### 2.2 请求与结果

```java
public final class Request {
    public final String   key;         // 逻辑文件名，交给 Source.Resolver
    public final File     target;
    public final Integrity integrity;  // 可为 null
    public final int      chunks;      // <=0 用全局默认
    public final boolean  allowResume;
}

public final class Probe {           // 与原实现同形，字段名保留
    public final long    total;
    public final String  etag;
    public final boolean rangeSupported;
}

public final class Result {
    public final long    totalBytes;
    public final String  etag;
    public final boolean chunkVerified;   // 是否逐块校验过
    public final Source  lastSource;
}
```

### 2.3 校验

```java
/** 整体 + 逐块。逐块是这个库的关键：没有它，切源就没法验证拼出来的东西是对的。 */
public final class Integrity {
    public final long     totalBytes;      // <=0 表示未知
    public final String   wholeSha256;     // 可空
    public final long     chunkSize;       // 逐块校验的块大小
    public final String[] chunkHashes;     // 按 chunkSize 切分后的逐块摘要

    public String hashFor(long start, long end);
}
```

> 原实现里这叫 `ChunkHashes`，由 `ChunkManifest.forFile(name)` 从固定位置取。
> **新 API 不去任何地方取**——调用方给。要不要提供一个「从 URL 拉 manifest」的
> 便利实现，放到 `anabranch-manifest` 这个可选模块里，不进核心。

### 2.4 观察与取消

```java
public interface Listener {
    default void onTotal(long total) {}
    default void onProgress(long soFar, long total) {}
    default void onSpeed(float bytesPerSec) {}          // 注意：不是 mbps
    default void onSourceSwitch(Source from, Source to, ChunkRef c, Throwable why) {}
    default void onChunkVerifyFailed(ChunkRef c, Source s) {}
}

public interface Cancellation { boolean isCancelled(); }
```

> **两处刻意的改动**：
> - 原 `Sink.onSpeed(float mbps)` 换成字节/秒。单位换算是调用方的事，库里做只会让
>   人猜是十进制 MB 还是 MiB。
> - 进度回调与取消**拆开**。原来挤在一个 `Sink` 里，导致「只想看进度」的调用方
>   也得实现 `isCancelled`。

---

## 三、引擎后端

```java
public interface Engine {
    Probe probe(String url, Cancellation c) throws IOException;
    /** 拉一个字节区间到 out。库负责分片划分、重试与切源；Engine 只管这一段。 */
    void  fetchRange(String url, long start, long endInclusive,
                     WritableByteChannel out, Listener l, Cancellation c) throws IOException;
}
```

两个实现，**分成两个 artifact**：

| artifact | 内容 | 许可 |
|---|---|---|
| `anabranch-core` | 调度、分块、校验、断点续传、`HttpEngine`（JDK / OkHttp 可选） | 你自己定 |
| `anabranch-aria2` | `Aria2Engine` + 双后端 failover + `.so` | **aria2 是 GPLv2+，静态链接后发二进制有源码提供义务** |

> 拆开的理由不只是许可：绝大多数使用者不需要 native，不该被迫吞一个 20MB 的 so。

### 3.1 aria2 的 dead-man's switch（这块设计直接搬）

原理值得原样保留，它解决的是一类真问题：**native 崩溃（SIGSEGV/SIGABRT）整体
杀进程，Java 层没有任何 catch 机会**，所以没法在 catch 里记录「刚才那个后端挂了」。

做法是把判据挪到进程外：

- 启动 native 之前，**先落盘一个 armed 标记**；
- 只有 RPC 确认干净关停后才 disarm；
- 下次启动发现标记仍是 armed → 上一轮死于非命 → 自动换另一个后端（openssl ↔ gnutls）。

两档故障分开处理：**加载期失败**（`UnsatisfiedLinkError`，可捕获）当场换，不等下次启动；
**运行期崩溃**只能靠标记。

```java
public interface BackendSelector {
    String  current();                 // 本次该用哪个后端
    void    arm(String backend);       // 落盘，必须先于任何 native 调用
    void    disarm();                  // 仅在确认干净关停后
    void    reportLoadFailure(String backend);
}
```

---

## 四、入口

```java
public final class Downloader {
    public static Builder newBuilder();

    public Result download(Request r, SourceSet sources,
                           Listener l, Cancellation c) throws IOException;

    /** 探测所有源，返回尺寸/etag 是否一致——不一致意味着镜像不同步，切源会拼出坏文件。 */
    public ProbeReport probeAll(String key, SourceSet sources, Cancellation c);

    public static final class Builder {
        public Builder engine(Engine e);
        public Builder defaultChunks(int n);
        public Builder maxConcurrentPerSource(int n);
        public Builder maxConcurrentTotal(int n);
        public Builder logger(Logger l);          // 默认 no-op
        public Builder stateDir(File dir);        // .part / .meta 落在哪；默认 target 同目录
        public Downloader build();
    }
}
```

> `probeAll` 是从原实现的 `probeAllSizes` 提出来的，**它比看上去重要**：多源下载
> 最阴的失败模式是某条镜像还是旧版本，尺寸一致但内容不同——逐块校验能兜住，
> 但先探一次能省掉整轮下载。

---

## 五、断点续传的落盘格式

原实现用 `partFileFor()` / `metaFileFor()` 两个旁文件。保留这个形状，但：

- **格式要写进文档并版本化**（原来是隐式约定，换个实现就读不了）；
- `.meta` 至少要有：`总长度 / etag / chunkSize / 每块状态（未下/已下/已验）/ 写入时的库版本`；
- **etag 变了必须整份作废**——服务端换了文件而续传接着写，是这类库最经典的静默损坏。

---

## 六、明确不做的事

划清楚边界，否则会长回成一个应用框架：

- ❌ 不做安装 / 解压 / 事务替换（原 `installArchive` 那一层，1,563 行，留在应用里）
- ❌ 不做 UI、通知、前台服务
- ❌ 不做重启 / 崩溃恢复流程
- ❌ 不内置任何 URL、不读任何配置文件、不碰 SharedPreferences
- ❌ 不做「包表」「热更」「离线包」这类领域概念

---

## 七、从旧代码到新库的对照

| 旧 | 新 | 处置 |
|---|---|---|
| `CNChunkedDownload`（1,350 行） | `anabranch-core` 分块与调度 | **净室重写**（原作者非你） |
| `ChunkManifest`（178 行） | `Integrity` + 可选 `anabranch-manifest` | **净室重写** |
| `CNMirrors` 的线路模型（1,169 行） | `Source` / `SourceSet` / `Stats` | 摘掉 native 钩子与本项目 schema 后改写 |
| `CNDownloaderFix` 下载侧（~1,017 行） | `Downloader` + `Policy` | **不搬**，逻辑重实现在新 API 上 |
| `CNDownloaderFix` 安装/UI 侧（1,563 行） | — | 丢弃 |
| `CNAria2` / `CNAria2Lib`（910 行） | `anabranch-aria2` | 改名迁移 |
| `Aria2EngineFailover`（212 行） | `BackendSelector` | 改名迁移 |
| `CNDownloadMode` / `CNDownloadConcurrency`（290 行） | Builder 选项 | 改名迁移 |

**账**：净室重写 ~1,530 行、重实现 ~1,000 行、改名迁移 ~2,400 行。

---

## 八、下一步的先后

1. **先写 `Engine` + `HttpEngine` + 单源分块**——不带多源，跑通断点续传与逐块校验。
   这一步就能独立验证，也是重写 `CNChunkedDownload` 的落点。
2. 再加 `SourceSet` / `Policy` / `Stats`，接上多源与切源。
3. 最后补 `anabranch-aria2`。

> 顺序的理由：多源是卖点，但它建立在「单源分块是对的」之上。反过来先做多源，
> 出了问题分不清是调度错了还是分块错了。
