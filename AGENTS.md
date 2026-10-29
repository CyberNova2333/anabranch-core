# Anabranch Core 协作约定

## 检查入口

| 情形 | 命令/要求 |
| --- | --- |
| 日常验证 | `sh tools/run-checks.sh`：Java 8 兼容编译、完整编译、core 边界、四个测试主类 |
| 修改选路、并发、重试或限速 | 完整检查连跑四轮，记录每轮最终退出状态 |
| 修改 native 后端 | 另跑 `sh aria2/native/run-host-tests.sh`，需 C++ 工具链和独立构建环境 |

编译通过不代表行为正确。测试用真实本机 HTTP 传输，不能全部 mock；策略的确定性规则用假的 Stats 直接测，端到端断言不要赌调度顺序。时间断言只取下界，不用负载相关上界。临时脚本放仓库外；长期检查接入 `tools/run-checks.sh`。

## 实现和兼容性红线

- `src/io/anabranch/` 纯 Java、零第三方依赖，只引用 `java.*`，不引用 Android/AndroidX。Android 的服务、通知、任务调度属于另一个 artifact。
- Java 8 字节码和 Android minSdk 21 同时约束；不使用 `java.util.function`、`stream`、`Optional`、`java.nio.file`、`java.time` 等目标环境缺少的运行时 API。lambda/default 方法可脱糖，不代表运行时类库也可用；不强迫下游配置 core-library desugaring。测试代码只在桌面 JVM 上运行。
- 保持 `Downloader → Policy/Stats + Engine` 分层；Engine 不引用调度层，Policy 只读 Stats、不做 IO。aria2 当前为独立下载器，不实现 Engine。
- aria2 必须是独立 artifact；保留 GPLv2+ 依赖、静态链接和源码提供义务，不能把它混入 core JAR。
- 独立实现，不从其它仓库复制代码；设计来源只用功能性描述，不写其它项目名，守卫和提交信息也不得写回这些名称。
- 不把无摘要传输说成内容已验证；同源 ETag 和跨源内容摘要边界必须保留。

## 分支、提交与签名

- 默认在正式 `main` 分支工作，无默认 PR 流程；用户未授权时不提交或推送。唯一例外为修复 main 检查失败的 `hotfix/<用途>`，全会话最多一条，合入后退役；不以新分支触发 CI。
- Conventional Commits：`type(scope): 中文描述`。允许 `feat fix refactor docs test chore ci perf build`，可用破坏性 `!`；一功能一提交，正文解释原因，结尾交代 `文档: 已更新…` 或具体不影响原因。
- 人类是作者，身份按实际参与者核对，没有默认值，不确定则先确认；Agent 仅作为实际 co-author，完整 `Name <email>`。已登记：`Claude <noreply@anthropic.com>`、`Codex <noreply@openai.com>`；新 Agent 首次贡献时登记。不用 CI bot 代人署名。
- 所有非初始提交必须 SSH 签名，不用未签名提交或 Agent 通用签名密钥；生成密钥时私钥只留本机，仅把公钥交给维护者登记。
- 不写具体模型档位/版本、会话链接/标记、凭据、私有仓名、内部域名、运行时端点、主机或资源管线细节；用中性功能表述。此约束同时适用于源码、注释、文档、提交与 PR。
- 历史 bundle 导入已非日常流程；`tools/rebase-bundle-to-main.sh` 仅在明确要求时使用。基线须为已验证 origin/main，保留 tree 一致性和本机签名验证；不得从历史流程推导改写或推送授权。

## 现有护栏

保留 `.claude/settings.json` → `tools/agent-guard.py` → `tools/githooks/` 接线；人工备用安装为 `sh tools/install-hooks.sh`。
`commit-msg`/`pre-push` 与 CI 检查中文 Conventional 标题、完整 co-author 和文档交代。POSIX sh 启动层及 `.gitattributes` 的 LF 必须保留；找不到 Python 时放行并提示。Agent guard 拦 `--no-verify` 和覆盖 hooksPath 的绕过。
已记录的人工逃生口是提交信息顶格独占一行 `[skip-hooks]`，及本地 `SKIP_MSG_HOOK=1`；后者不豁免 CI。它们不构成默认绕过授权。护栏是防误操作机制，不能宣称本机不可绕过的安全边界。

本文件是项目规则单一来源；发现规则冲突先说明，遵守当前明确任务授权与上级指令，不让通用分支模板自动改变本项目工作流。


## Repo docs

The living guide is [repo-docs/README.md](repo-docs/README.md); start with the [main walkthrough](repo-docs/walkthroughs/one-real-run.md), then use the [code map](repo-docs/code-map.md) to locate changes.
This repo's `repo-docs/` guide is reader-facing Chinese documentation. When updating reader-facing guide pages, use `repo-docs-zh` when available; keep Chinese reader handles in the prose and preserve exact source identifiers for lookup.
Read current source, scripts, configuration and tests before relying on guide claims. Repo questions, behavior-changing edits, user uncertainty, stable knowledge clarified in conversation and knowledge about to enter memory trigger the `repo-docs` Sync gate before the final response (or the same manual check if the skill is unavailable).
Choose `none`, `answer-only`, `foreground patch`, or `background sync`. Patch the smallest owning page before answering when stale content would mislead; delegate broader non-critical sync only with a real tracked handoff. Record meaningful guide changes and validation limits in `repo-docs/change-log.md`, with `Synced through <sha>` when HEAD exists. Do not log every answer.


## 贡献与维护入口

提交采用 Conventional Commits：`type(scope): 描述`；项目已有的中文描述、署名、签名和文档交代要求继续生效。保留用户未提交内容，只暂存本次明确审查的路径。
贡献与安全约定分别见 [贡献说明](CONTRIBUTING.md) 和 [安全说明](SECURITY.md)；相关维护由统一任务协调，避免并行覆盖。
维护守卫安装命令为 `python tools/repo_maintenance_guard.py install`。仅在明确安装任务下执行；文档修改不自动安装或更改 Git 配置。保留已有安全钩子，安装与验证结果按实际执行另行记录。

文档站统一使用 VitePress 默认主题；样式与动效遵循原生主题，构建输出独立于业务界面。
