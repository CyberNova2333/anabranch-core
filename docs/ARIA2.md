# anabranch-aria2 · 设计稿

> 状态：**设计 + Java 侧骨架**。native 侧（NDK 交叉编译 libaria2 两个 TLS 后端）
> 在远程开发环境里做不了，这份文档把设计定下来，让接下来在本机做 native 的人
> 只需要照着填。
>
> Java 侧骨架在 `aria2/` 目录下；其中 dead-man's switch（`FileBackendSelector`）
> 是**完整实现并带测试**的——它是纯 Java 文件协议，现在就能验证，没理由留成占位。

---

## 〇、为什么要 native 后端

纯 Java 的 `HttpEngine` 已经够正确，aria2 后端要的是三样它给不了的东西：

1. **成熟的连接管理**——连接复用、DNS 缓存、对烂网络的容忍，aria2 被真实世界
   捶打了二十年；
2. **低端设备上的吞吐**——Java 层每字节都要过一遍 JNI/堆拷贝，native 直写省掉；
3. **兑现「能换引擎」的承诺**——分层（DESIGN.md §三）的价值要靠第二个引擎证明。

第 3 条在写这份稿子时被部分证伪了，见下一节——这正是先写设计稿的意义。

## 一、许可与 artifact 边界

- aria2 是 **GPLv2+**。静态链接后分发二进制（AAR 里带 `.so`）有源码提供义务。
  所以它**必须是独立 artifact**（core 铁律 4），让只走纯 Java 路线的人零牵连。
- Java 侧胶水代码是我们自己写的，不含任何 aria2 代码，保持 Apache-2.0；
  将来含 `.so` 的**二进制分发**整体按 GPL 的义务走（提供对应源码与构建脚本）。
- **先同仓孵化**（`aria2/` 子目录，独立源树、不进 core jar、Gradle 不碰它），
  共用这里的守卫与钩子；发不发独立仓库等 native 侧成型再定。发布形态是 AAR
  （要装 `.so`），坐标预定 `io.anabranch:anabranch-aria2`。

## 二、集成形态：Engine 假设的修正（本稿最重要的一节）

DESIGN.md §三 假设 aria2 以 `Aria2Engine implements Engine` 接入——「取一个
字节区间」。**这个假设对 aria2 大概率不成立**：aria2 自己管分段与断点，
不提供「下载指定字节区间」的选项；调用方塞 `Range` 头会与它自己发的 Range
冲突，行为未定义。这不是实现难度问题，是在逆着工具的纹理用它。

所以定两个形态，主次分明：

### 形态 1（基线）：`Aria2Downloader`——整文件、多 URI，调度交给 aria2

aria2 原生就是多源下载器：`addUri([url1, url2, …])` 一条下载挂 N 个镜像，
分段、切源、重试都是它自己的。基线形态就顺着这个纹理来：

- 对外暴露与 core 相同的公共面：`download(Request, SourceSet, Listener,
  Cancellation)`，`Source.Resolver` 逐源解析出 URL 交给 aria2。**使用者换后端
  不改调用代码**——这是「能换」在 aria2 上真正兑现的层级。
- core 的 `Policy`/`Stats` 在此形态下**不参与**：aria2 有自己的选路。文档与
  javadoc 必须写清楚，别让人以为自定义 Policy 还生效。`Source.weight` 映射不了，
  忽略并在日志里说明。
- **逐块校验仍然是我们的**：aria2 下完后、rename 提交前，按 `Integrity` 逐块
  自验（aria2 的 checksum 选项只有整份且格式受限）。校验不过不提交——与 core
  同一条铁律。
- 进度经 libaria2 的事件/轮询接口喂给 `Listener`，单位与语义与 core 一致
  （字节/秒、最近窗口速度）。

### 形态 2：`Aria2Engine implements Engine`——已实测判死（2026-09-01）

原计划：若「`split=1` + 单连接 + 自定义 `Range` 头」在目标 aria2 版本上行为
可靠，则 Engine 形态复活。实测（aria2 1.37.0，对着会记录请求头的本地 HTTP
服务器）结果比预判还糟一档：

- aria2 确实把自定义 `Range: bytes=1000-1999` **原样发出**，也把服务端返回的
  1000 字节正确写进了输出文件——到这里看似可行；
- 但它把 206 响应 `Content-Range` 里的**总长**（`…/10000`）当作预期下载量，
  认为还差 9000 字节，于是**无限等待**：不报错、不退出、留下 `.aria2` 控制
  文件。对照组（无自定义头）完全正常。

挂起而不是报错，是下载库最坏的失败形态——连「失败了换条路」的机会都没有。
按本节原来立的规矩执行：`Aria2Engine` 已删除，不留半活的路径。基线形态
（`Aria2Downloader`）是唯一形态。桌面版与交叉编译版行为一致性由「钉住同一
aria2 版本」保证；若将来换的版本在 changelog 里明确改了 Range 语义，可以
重开这个实验，判据与复现步骤如上。

## 三、进程内 libaria2，不是子进程 aria2c

两条路都走得通，选**进程内 JNI（libaria2）**：

| | 进程内 libaria2 | 子进程 aria2c + JSON-RPC |
|---|---|---|
| native 崩溃 | **整个进程一起死**（下一节的理由） | 隔离，app 活着 |
| 攻击面 | 无网络接口 | localhost 端口 + secret，得管好 |
| 生命周期 | 与 app 同生死，简单 | 后台进程被系统另行管控，状态易漂 |
| 部署 | `.so` 装进 AAR，标准路径 | 可执行文件伪装成 `lib*.so`，各厂商 ROM 上碎片化 |
| 早先实践 | **踩过坑、配套设计成熟** | 没有积累 |

子进程的崩溃隔离确实诱人，但它把复杂度换了个地方：端口冲突、RPC secret 的
存放、进程被系统杀掉后的对账，每一条在碎片化的 Android 生态上都是长尾。
进程内崩溃的问题有专门解法，就是下一节。

> **控制面订正（2026-09-01）**：早先实践的进程内形态其实是「argv 驱动 aria2c
> 主循环 + 进程内 RPC 控制」——RPC 的端口与 secret 问题它同样背着。本库不再
> 走那条：改用 libaria2 的**公开 session API**（`--enable-libaria2`），控制面
> 就是 JNI 函数本身——无 RPC、无端口、无 secret，上表「无网络接口」这格从
> 此是真的。事件循环由 Java 侧单线程反复调 `runOnce` 驱动，取消 = 两次
> `runOnce` 之间调 `sessionShutdown`，不存在跨线程调 libaria2（它不是线程
> 安全的）。公开 API 也没有 argv 解析路径，连「参数错直接 exit() 杀进程」
> 这档坑都一并绕开。

## 四、dead-man's switch（设计直接搬，已实现）

**问题**：native 崩溃（SIGSEGV/SIGABRT）整体杀进程，Java 层没有任何 catch 的
机会——「上一轮是哪个后端弄死我们的」这个判据没法在进程内记录。

**解法**：把判据挪到进程外（落盘），两档故障分开处理：

- **运行期崩溃**（进程直接没了）：启动 native **之前**先把「armed + 后端名」
  写盘并 fsync；只有 RPC/session 确认干净关停后才 disarm。下次启动发现标记
  仍在 → 上一轮死于非命 → 换另一个后端（openssl ↔ gnutls）。
- **加载期失败**（`UnsatisfiedLinkError`，可捕获）：当场 `reportLoadFailure`
  换后端重试，不等下次启动。

`FileBackendSelector` 的落盘协议（`stateDir` 下两个文件）：

- `aria2.backend`——当前偏好的后端名；
- `aria2.armed`——armed 标记，内容是武装时的后端名。

消费时序：`current()` 发现 armed 标记 → 先把偏好写成**另一个**后端（切换要
粘住，否则每次启动来回抖）→ 再删标记（这次的判词已消费）。若在两步之间再次
崩溃，下次重复同一流程，结果一致——协议是幂等的。两个后端都坏时会交替尝试，
这是接受的行为：总得选一个，交替至少不会永远吊死在同一个坏后端上。

为什么两个 TLS 后端：TLS 库的崩溃与握手兼容性问题是 native 侧最大的故障源，
且往往只在特定 ROM/证书链组合上出现——同一份 aria2 配两个 TLS 实现，坏了能切，
比祈祷单个库处处正常靠谱。

## 五、Java 侧骨架清单

| 类 | 状态 | 说明 |
|---|---|---|
| `BackendSelector` | 接口，定稿 | 上一节的四个方法 |
| `FileBackendSelector` | **完整实现 + 测试** | 落盘协议如上；无 nio.file（minSdk 21 同样适用） |
| `Aria2Downloader` | 骨架 | 形态 1 的公共面；native 未接入前抛 `UnsupportedOperationException` |
| `Aria2Engine` | **已删除** | 形态 2 实测判死（见 §二），按「不留半活路径」的规矩移除 |
| `Aria2Native` | JNI 表面 | libaria2 C API 的最小子集：session 建立/关停、addUri、轮询进度 |

`aria2/` 的源码同样跑 `--release 8` 编译（Android artifact，minSdk 21 的约束
一条不少）；`tools/run-checks.sh` 已接上骨架编译与 selector 测试。

native 侧文件（`aria2/native/`，2026-09-01 落地）：

| 文件 | 说明 |
|---|---|
| `aria2_jni.cpp` | JNI 胶水，走 `<aria2/aria2.h>` 公开 API，已对着真头文件过类型检查 |
| `libaria2-anabranch.map` | version script：恰导出 8 个 JNI 符号 @@ANABRANCH_ARIA2_1.0 |
| `compat_arm.c` | armv7 的 fseeko/ftello 兜底（32 位 bionic 是 API 24 才有） |
| `build-aria2.sh` | 交叉编译 + 构建后自检（符号卫生 / NEEDED / 16K 对齐 / stub 差集） |

## 六、native 侧构建（已落地为 CI，待跑通验证）

`.github/workflows/build-aria2.yml`（**workflow_dispatch 手动触发**，产物只上传
artifact、不入库不发版）：NDK r25c 与 9 个依赖 tarball 全部 sha256 钉死，
aria2 1.37.0——与 §二 Range 实测钉的是同一版本。配方来自早先在另一个项目里
逐坑验证过的构建（gmp 的 PIC 汇编、nettle 在 armv7 禁 NEON、libxml2 去 iconv、
16K 页对齐、armv7 对 API-21 stub 的 UND 差集硬校验，全部保留）；本仓库的
差异只在胶水层：`--enable-libaria2` 走公开 API、导出符号换成 anabranch 的 8 个、
去掉了不需要的控制台日志补丁。

剩余（按序）：CI 首跑验证 → 真机装载与下载验证 → AAR 打包 → 二进制分发前
落实 GPL 义务（第三方 NOTICES、构建可复现说明）。

## 七、测试计划

- **现在**：`Aria2FailoverTest`——selector 全部状态转移（纯文件 IO，无网络，
  「不许 mock 网络」对它不适用，因为它根本不碰网络）。
- **native 接入后**：与 core 同一套打法——真起本地 HTTP 服务器，对着
  `Aria2Downloader` 跑下载/多镜像/校验失败/取消；桌面 JVM 上载入桌面版
  libaria2 跑，Android 上进 instrumented test。

## 八、开放问题

1. ~~Range 实测（决定形态 2 存亡）~~——已做，形态 2 判死，见 §二；
2. ~~`Cancellation` 映射~~——随单线程 runOnce 模型解决（见 §三 订正）：取消在
   两次 runOnce 之间调 `sessionShutdown`，同线程、无竞态；及时性待真机量一下；
3. 进度粒度：轮询周期取多少才能既喂饱 `onProgress` 又不把 JNI 调用变成热点；
4. `.meta` 与 aria2 自己的 `.aria2` 控制文件的关系——形态 1 下续传状态以谁为准
   （倾向：完全交给 aria2，`.meta` 只在 core 路径存在）；
5. 依赖面裁剪：配方按已验证形态保留了 libxml2/sqlite3/libssh2（metalink、
   Firefox cookie、SFTP），对一个 HTTP 下载后端未必都需要——裁剪能明显缩小
   `.so` 与构建时长，但每裁一刀都是对已验证配方的偏离，等 CI 首跑通了再议。
