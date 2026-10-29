# Anabranch Core：源码阅读指南

把同一个文件拆为可调度的块，从多条源取字节；失败重试、续传与可选摘要校验都在提交成品前完成。核心是独立 Java 库，Android 接线和 native 后端有各自边界。

本指南采用 Lite 包加代码地图，聚焦 **纯 Java 下载器从请求、源探测到分块校验与成品提交**。aria2 独立 artifact、JNI/native 构建与 Android 真机接线仅列入口，不追踪其实现；Maven 发布、包签名和历史 bundle 导入不在主流程范围。

## 阅读路径

| 读者目标 | 从这里开始 | 读完后获得什么 |
| --- | --- | --- |
| 第一次理解行为 | [沿一次真实操作走到底](walkthroughs/one-real-run.md) | 看懂输入、阶段交接、输出和失败边界 |
| 已了解行为，准备改代码 | [按职责寻找改动入口](code-map.md) | 找到范围内实现、测试和未覆盖区域 |
| 审核证据 | [核对两遍源码调查](references/source-evidence.md) | 区分源码事实、测试意图与未跑验证 |
| 检查讲解是否足够 | [查看读者模拟与剩余风险](references/quality-review.md) | 判断哪些结论需要补充验证 |
| 查看同步历史 | [查看指南变更](change-log.md) | 确认同步基线和本轮范围 |

## 运行与验证

准备 JDK 17 或 21、Python 3 和 POSIX shell。脚本直接调用 JDK，不需要 Gradle wrapper：

```sh
sh tools/run-checks.sh
```

它先做 Java 8 兼容编译，再编译并运行主类驱动的测试与边界检查；测试启动真实的本机 HTTP 服务。native 后端修改另需 `sh aria2/native/run-host-tests.sh`，本轮不执行耗时下载或 native 构建。

这里给出的业务命令来自现有源码或脚本；本轮只执行文档结构校验与站点构建，没有运行业务测试、安装钩子、停止服务或部署。测试文件的存在表示可检查的验证意图，不表示本轮通过。

## 文档站

文档站采用 VitePress 默认主题，依赖与业务项目分开安装。默认输出 `docs-site/.vitepress/dist/`；独立核验可在构建命令后加 `-- --outDir <仓库外绝对目录>`。指南正文只维护 `repo-docs/`，站点通过 include 读取；网页中的仓库源码链接显示为精确文件路径，不复制源码或本机资源。

```sh
npm --prefix docs-site ci
npm --prefix docs-site run docs:dev -- --host 127.0.0.1
npm --prefix docs-site run docs:build
```

独立网页构建会把文档目录之外的仓库链接显示为精确文件路径，避免产生失效跳转；原始 Markdown 仍保留可核对的源码链接。站点只发布指南，源码和本机资源不会随文档打包。

证据状态：除特别标注外，本页基于当前源码已确认。
