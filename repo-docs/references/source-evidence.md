# 源码证据审计

这是固定审计记录，记录 2026-10-29 的两遍调查；行为解释由主流程页承载，源码导航由代码地图承载。

## 遍历记录

| 遍历 | 实际检查 | 得到的依据 |
| --- | --- | --- |
| Pass 1 | 仓库目录、Git 状态、父级及 root AGENTS/CLAUDE、README、`tools/run-checks.sh`、`build.gradle.kts`、`src/io/anabranch/Downloader.java` 与 `Request.java` | 确定真实入口、运行命令及现存文档；README 只作线索 |
| Pass 2 | `Downloader.download`、块摘要失败分支与 `commit`、`ResumeState` 的元数据及 ETag 规则，以及 `test/SingleSourceTest.java`、多源和限速测试入口 | 追踪主路径的输入、输出与失败分支，并对照测试断言 |

## 覆盖与排除

覆盖：纯 Java 下载器从请求、源探测到分块校验与成品提交。aria2 独立 artifact、JNI/native 构建与 Android 真机接线仅列入口，不追踪其实现；Maven 发布、包签名和历史 bundle 导入不在主流程范围。


## 声明与证据

| Claim | Evidence | Confidence | Caveat | Used by |
| --- | --- | --- | --- | --- |
| 内容摘要由调用方提供且可选 | [请求](../../src/io/anabranch/Request.java)、[校验](../../src/io/anabranch/Downloader.java) 搜索 `r.integrity` | 已确认 | 无摘要不能保证内容同一 | 主流程 Step 1、3 |
| 同源 ETag 与跨源续传不同 | [续传状态](../../src/io/anabranch/ResumeState.java) 搜索 `matches` | 已确认 | 跨源长度相同仍需可信摘要 | 主流程 Step 2 |
| 目标替换不是跨文件系统原子保证 | [提交实现](../../src/io/anabranch/Downloader.java) 搜索 `target.delete`、`renameTo` | 已确认 | 失败抛错，旧目标可能已删除 | 主流程 Step 4 |
| 日常检查运行四个测试主类 | [脚本](../../tools/run-checks.sh) | 已确认 | 本轮未跑；不覆盖真 native 下载 | 主流程 Step 5 |

## 证伪检查与读者下一问

证伪检查：若坏块仍标记 VERIFIED、缺块仍能提交，或把不同源 ETag 当作强一致依据，指南就失效。应重新读校验与续传分支，并运行现有真实 HTTP 回归。

读者下一问：native 后端怎样独立打包并满足许可义务？从 `docs/ARIA2.md`、`aria2/THIRD-PARTY-NOTICES.md` 和 native 脚本另行追踪，不能从 core 测试推导真机结果。

## 验证边界

本轮执行 `validate_repo_docs.py` 和 MkDocs 构建，退出码及完整输出保存在独立维护报告中；不将静态阅读当作业务测试通过。没有部署、生产验收或历史改写。
所有 `../../` 源码链接按当前磁盘核对；独立站点没有这些仓库文件，MkDocs 对此记 INFO，站内链接仍正常检查。未解析、展示或引用真实凭据和运行时数据库内容；现有未提交文件仅作本地摘要保全。
