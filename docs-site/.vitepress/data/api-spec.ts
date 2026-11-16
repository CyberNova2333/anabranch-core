import type { ApiCatalogSpec } from './api-types'

export const apiSpecZh: ApiCatalogSpec = {
  project: 'Anabranch Core · Java 8 多源分块 SDK 参考',
  subtitle:
    '覆盖零三方依赖的纯 Java 8 多镜像分块下载核心（io.anabranch.*，19 源文件、兼容 Android minSdk 21）、自定义 Engine/Policy/Resolver 扩展契约及可选 GPL-3.0 aria2 JNI 后端。',
  environments: [
    { label: '纯 Java 8 核心坐标', value: 'io.github.cybernova2333:anabranch-core:0.1.0' },
    { label: '可选 aria2 AAR 坐标', value: 'io.github.cybernova2333:anabranch-aria2:0.1.0' },
  ],
  searchPlaceholder: '搜索 Downloader、SourceSet、Integrity、Policy、aria2…',
  emptyText: '没有找到匹配的 Java 类或扩展接口。',
  labels: {
    allGroups: '全部',
    canDo: '✅ 能做到',
    cannotDo: '⛔ 明确限制 / 拒绝条件',
    params: '类字段、方法参数与边界',
    paramName: '字段 / 方法',
    paramLocation: '作用域',
    paramType: '类型',
    paramRequired: '要求',
    paramDesc: '契约语义与限制',
    responses: '返回值与异常边界',
    examples: '标准 Java 调用示例',
    responseSample: '典型返回 / 状态文件示例',
    copy: '复制',
    copied: '已复制',
    noParams: '无额外必传参数。',
    required: '必填',
    optional: '可选',
    deepDocs: '查看公开 API 完整规范',
    totalItems: '项公开类/契约',
    envLabel: 'Maven 制品视口',
    mobilePicker: '切换类或扩展点',
    defaultLabel: '默认',
    plainIndexToggle: '纯文本检索与打印索引（Ctrl+K 站内搜索定位）',
  },
  groups: [
    { id: 'core-sdk', title: '核心调度 SDK (io.anabranch)' },
    { id: 'ext-aria2', title: '扩展接口与 aria2 后端' },
  ],
  items: [
    {
      id: 'java-downloader-request',
      group: 'core-sdk',
      kind: 'CLASS',
      title: 'Downloader 协调器与 Request / Integrity 模型',
      signature: 'Downloader.newBuilder().build().download(req, sources, listener, cancel)',
      auth: 'AGPL-3.0-only · 纯 Java 8 (--release 8)',
      stability: '核心稳定 API',
      summary:
        '线程安全可复用协调器：按 SourceSet 与 Policy 调度多镜像并发分块，失败单块默认至多 6 次（2000ms 退避 + 60s 失败时间预算），配合 .part 与 anabranch-meta/2 实现跨源断点续传及逐块 SHA-256 校验。',
      relatedLink: '/docs/contracts',
      relatedLabel: '查看 Result、Integrity 与探测细节',
      capabilities: [
        '纯 Java 8（HttpURLConnection）19 个源文件零 android.* 与三方依赖，Android minSdk 21 免 desugaring 直用',
        '支持 probeAll() 预检镜像一致（etagsAgree 仅作参考不阻断跨源），并区分 resumed 与 full 的 transferredBytes',
      ],
      limitations: [
        'Maven Central 发布为手动流程，接入前需确认已发布或直接编译 src/；Source.maxChunks 当前仅为元数据字段，尚未做每源并发硬限',
        '采用 Cancellation 轮询而非 Thread.interrupt()；无状态库刻意不内置镜像 URL、不持久化全局配置、不解包/安装 APK 也不提供 UI',
      ],
      params: [
        { name: 'Request(key, target, integrity, chunks, allowResume)', location: 'arg', type: 'Request', required: true, description: 'Request.of(...) 省略校验摘要并开启 allowResume=true；有 chunkHashes 时分块大小对齐 integrity.chunkSize。' },
        { name: 'Builder (defaultChunks / maxConcurrent / chunkRetryBudgetMs)', location: 'config', type: 'Builder', required: false, defaultValue: 'budget = 60000 ms', description: 'chunkRetryBudgetMs 只累计失败重试时间开销，不惩罚健康慢传；单源连续失败满 3 次即在本轮禁用。' },
        { name: 'Listener / Cancellation', location: 'arg', type: 'callbacks', required: false, defaultValue: 'NONE / NONE', description: 'onSpeed 单位严格为 bytes/sec；Cancellation 贯穿读循环、限速与退避睡眠。' },
      ],
      responses: [
        {
          status: 'Result { totalBytes, etag, chunkVerified, transferredBytes }',
          tone: 'ok',
          summary: 'wholeSha256 与全块就绪原子重命名落盘后返回（etag 永不为 null，缺省为空串 ""）',
          example: `Result {
  totalBytes: 10485760,
  etag: "\\"abc123\\"",
  chunkVerified: true,
  transferredBytes: 2621440
}`,
        },
        { status: 'IOException / Cancelled', tone: 'err', summary: '超过重试预算、SHA-256 校验失败或主动取消即中止（保留合法 .part/.meta 待续传）' },
      ],
      examples: [
        {
          label: 'Java 8 SDK',
          lang: 'java',
          code: `Downloader dl = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .chunkRetryBudgetMs(60_000)
    .build();

SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("cdn-a", "https://cdn-a.example.test/pkg/"),
    Source.ofBase("cdn-b", "https://cdn-b.example.test/pkg/")),
    Policy.fastestFirst());

Result res = dl.download(
    Request.of("app.bin", new File("out/app.bin")),
    sources, Listener.NONE, Cancellation.NONE);`,
        },
      ],
    },
    {
      id: 'ext-engine-policy',
      group: 'ext-aria2',
      kind: 'SDK',
      title: '自定义 Engine、Policy 与 Source.Resolver 扩展契约',
      signature: 'interface Engine · interface Policy · interface Source.Resolver',
      auth: '受 tools/check-core-boundaries.py 守卫',
      stability: '扩展接口',
      summary:
        '允许替换传输引擎（如 OkHttp）、实现动态预签名镜像解析（Resolver）或定制基于 EMA 吞吐/并发负载的熔断选源策略。',
      relatedLink: '/docs/contracts',
      relatedLabel: '查看引擎与策略细则',
      capabilities: [
        'Policy.pick() 与 Policy.onFailure() 可读取每个源的 emaBytesPerSec、consecutiveFailures、inFlight、isDisabled 状态并返回 4 种决策',
        'Source.Resolver 支持每次请求动态签名 CDN token 或差异化镜像路径',
      ],
      limitations: [
        '自定义 Engine 实现在 start > 0 收到 HTTP 200（服务端忽略 Range）时必须拒收报错；写入字节数必须精确等于 endInclusive - start + 1',
        'src/io/anabranch/ 下严禁出现任何 android.* 与三方依赖；Engine 禁止反向依赖 Downloader/SourceSet/Policy/ResumeState',
      ],
      params: [
        { name: 'Policy.onFailure -> Decision', location: 'method', type: 'RETRY_SAME | SWITCH_SOURCE | DISABLE_SOURCE | ABORT', required: true, description: '返回失败后的明确恢复指令。' },
      ],
      responses: [
        { status: 'Engine.fetchRange(...) == exact bytes', tone: 'ok', summary: '写入 exact range 字节并实时回调 Listener 与检查 Cancellation' },
      ],
      examples: [
        {
          label: '本地边界检查 CLI',
          lang: 'sh',
          code: `sh tools/run-checks.sh
# 编译 core/aria2 Java 8 字节码、检查单向依赖边界并运行 loopback HTTP 集成测试`,
        },
      ],
    },
    {
      id: 'aria2-jni-backend',
      group: 'ext-aria2',
      kind: 'SDK',
      title: '可选独立 aria2 JNI 后端 (Aria2Downloader)',
      signature: 'io.anabranch.aria2.Aria2Downloader',
      auth: 'GPL-3.0-or-later 独立 AAR 隔离',
      stability: '可选大吞吐后端',
      summary:
        '复用相同的 Request、SourceSet、Listener、Cancellation 与 Result 签名无缝切换原生 libaria2 传输，通过独立模块打包避免向纯 Java 用户传染 GPL 许可。',
      relatedLink: '/docs/deployment',
      relatedLabel: '查看 aria2 构建与语义差异',
      capabilities: [
        '内部通过 urlFor(key) 聚合所有镜像 URI 交给 addUri 原生调度，带 200ms 回调节流与 10s 退出排水上限',
      ],
      limitations: [
        '全局通过 BUSY 锁串行执行单线程 runOnce 轮询；核心 Policy、Stats 与 Source.weight 不参与 libaria2 选路',
        '完整性校验只在 .a2part 下载完成后触发，验签失败会直接清理 .a2part 与 .a2part.aria2（网络错误保留续传）',
      ],
      params: [
        { name: 'Request.chunks', location: 'field', type: 'int (> 0)', required: false, description: '正数值直接映射为 aria2 split 分片参数。' },
      ],
      responses: [
        { status: 'Result (post-verified)', tone: 'ok', summary: '校验整份文件完成后原子重命名为目标文件' },
      ],
      examples: [
        {
          label: '宿主构建测试 libaria2',
          lang: 'sh',
          code: `sh aria2/native/run-host-tests.sh`,
        },
      ],
    },
  ],
}

export const apiSpecEn: ApiCatalogSpec = {
  project: 'Anabranch Core · Java 8 SDK & SPI Reference',
  subtitle:
    'Interactive reference for the zero-dependency Java 8 / Android minSdk 21 segmented downloader (io.anabranch.*), Engine/Policy/Resolver SPIs, and optional GPL-3.0 aria2 JNI backend.',
  environments: [
    { label: 'Core Artifact (AGPL-3.0-only)', value: 'io.github.cybernova2333:anabranch-core:0.1.0' },
    { label: 'Optional aria2 AAR (GPL-3.0+)', value: 'io.github.cybernova2333:anabranch-aria2:0.1.0' },
  ],
  searchPlaceholder: 'Filter Downloader, SourceSet, Integrity, Policy, aria2…',
  emptyText: 'No matching class or extension contract found.',
  labels: {
    allGroups: 'All',
    canDo: '✅ Capabilities',
    cannotDo: '⛔ Explicit Limits / Rejections',
    params: 'Parameters, Fields & Bounds',
    paramName: 'Symbol / Field',
    paramLocation: 'Scope',
    paramType: 'Type',
    paramRequired: 'Rule',
    paramDesc: 'Contract & Constraints',
    responses: 'Return Values & Failure Semantics',
    examples: 'Standard Usage',
    responseSample: 'Sample Return / State',
    copy: 'Copy',
    copied: 'Copied',
    noParams: 'No additional parameters.',
    required: 'Required',
    optional: 'Optional',
    deepDocs: 'Open public API docs',
    totalItems: 'public specs',
    envLabel: 'Maven Artifact Scope',
    mobilePicker: 'Select Class or SPI',
    defaultLabel: 'Default',
    plainIndexToggle: 'Plaintext Search & Print Index (Ctrl+K Target)',
  },
  groups: [
    { id: 'core-sdk', title: 'Core Java 8 SDK (io.anabranch)' },
    { id: 'ext-aria2', title: 'Extension SPI & aria2 Backend' },
  ],
  items: [
    {
      id: 'java-downloader-request',
      group: 'core-sdk',
      kind: 'CLASS',
      title: 'Downloader, Request & Integrity Contracts',
      signature: 'Downloader.newBuilder().build().download(req, sources, listener, cancel)',
      auth: 'AGPL-3.0-only · Pure Java 8 (--release 8)',
      stability: 'Core Stable API',
      summary: 'Thread-safe multi-mirror chunk scheduler supporting 6 retries/chunk, 2000ms backoff, 60s failure-only budget, .part + anabranch-meta/2 resume, and per-chunk SHA-256 verification.',
      relatedLink: '/en/docs/contracts',
      relatedLabel: 'Inspect Result, ProbeReport & Integrity',
      capabilities: [
        '19 plain Java 8 files with zero android.* or third-party deps (minSdk 21 compatible without desugaring)',
        'Supports probeAll() mirror inspection and reports resumed vs full transferredBytes',
      ],
      limitations: [
        'Maven Central release is manual (verify availability or compile src/); Source.maxChunks is currently metadata-only',
        'Stateless library: omits default URLs, persistent settings, archive unpacking, APK installation, and UI',
      ],
      params: [{ name: 'Request / Builder / Listener / Cancellation', location: 'arg', type: 'Core structs', required: true, description: 'chunkRetryBudgetMs defaults to 60_000 ms; onSpeed reports bytes/sec.' }],
      responses: [{ status: 'Result { totalBytes, etag, chunkVerified, transferredBytes }', tone: 'ok', summary: 'Non-null etag ("" fallback) and verified renamed file' }],
      examples: [{ label: 'Java 8 Call', lang: 'java', code: `Downloader dl = Downloader.newBuilder().defaultChunks(8).maxConcurrent(4).build();\nResult res = dl.download(Request.of("pkg.bin", new File("out/pkg.bin")), SourceSet.single("https://cdn.example.test/pkg.bin"), Listener.NONE, Cancellation.NONE);` }],
    },
    {
      id: 'ext-engine-policy',
      group: 'ext-aria2',
      kind: 'SDK',
      title: 'Custom Engine, Policy & Source.Resolver SPIs',
      signature: 'interface Engine · interface Policy · interface Source.Resolver',
      auth: 'Guarded by tools/check-core-boundaries.py',
      stability: 'Extension SPI',
      summary: 'Pluggable HTTP transport (e.g., OkHttp), dynamic pre-signed mirror resolution, and EMA/in-flight failover selection.',
      relatedLink: '/en/docs/contracts',
      relatedLabel: 'View Engine & Policy rules',
      capabilities: ['Supports custom Retry/Switch/Disable/Abort policies reacting to live Stats'],
      limitations: ['Custom Engines must reject HTTP 200 when start > 0 and remain strictly free of Downloader/ResumeState imports'],
      params: [{ name: 'Policy.Decision', location: 'method', type: 'RETRY_SAME | SWITCH_SOURCE | DISABLE_SOURCE | ABORT', required: true, description: 'Returned by onFailure(...).' }],
      responses: [{ status: 'Exact Range Bytes', tone: 'ok', summary: 'Rejects partial or overflowing upstream reads' }],
      examples: [{ label: 'Verify Java 8 & Boundaries', lang: 'sh', code: `sh tools/run-checks.sh` }],
    },
    {
      id: 'aria2-jni-backend',
      group: 'ext-aria2',
      kind: 'SDK',
      title: 'Optional Isolated aria2 JNI Backend (Aria2Downloader)',
      signature: 'io.anabranch.aria2.Aria2Downloader',
      auth: 'Separate GPL-3.0-or-later AAR',
      stability: 'Optional Native Engine',
      summary: 'Drop-in native libaria2 backend reusing Request/SourceSet/Listener/Result while isolating GPL licensing from core users.',
      relatedLink: '/en/docs/deployment',
      relatedLabel: 'View aria2 semantics & build',
      capabilities: ['Batches all mirror URIs to addUri with 200ms callback throttling and 10s shutdown drain'],
      limitations: ['Serializes calls process-wide via BUSY lock; ignores Core Policy/Stats; verifies integrity only post-download on .a2part'],
      params: [{ name: 'Request.chunks', location: 'field', type: 'int', required: false, description: 'Maps directly to aria2 split parameter.' }],
      responses: [{ status: 'Verified Result', tone: 'ok', summary: 'Deletes .a2part & .a2part.aria2 on hash mismatch' }],
      examples: [{ label: 'Host Native Test', lang: 'sh', code: `sh aria2/native/run-host-tests.sh` }],
    },
  ],
}
