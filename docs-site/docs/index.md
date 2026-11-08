---
title: 快速入门
description: Anabranch Core 是一个面向个人主开发命名空间的**多代码托管平台基线对齐与增量重签镜像同步引擎**。
---

# 快速入门

Anabranch Core 是一个面向个人主开发命名空间的**多代码托管平台基线对齐与增量重签镜像同步引擎**。

## 解决的核心痛点

- **痛点 1**：跨 GitHub 与备用冷备平台同步时，容易误用不兼容的 SSH 签名密钥或泄露专用邮箱
- **痛点 2**：手动 cherry-pick 或两端 push 容易导致 Commit SHA 冲突、历史断裂与文件树不一致
- **痛点 3**：缺乏严格的机器校验确保两端工作区 SHA-256 散列严格一一对齐

---

## 环境准备

Python 3.10+，Git 2.34+（支持 SSH 提交签名）。

---

## 快速安装与运行

```bash
# 1. 检查当前仓库的双端状态
python dual_push.py status --repo /path/to/repo

# 2. 将本地最新的 GitHub 主干增量重签并同步至备用镜像
python dual_push.py push --repo /path/to/repo

# 3. 验证双端 SHA-256 文件树一致性
python dual_push.py verify --repo /path/to/repo
```

---

## 接下来探索

- 深入了解系统内部流转：查阅 [系统架构与设计](/docs/architecture)
- 探索全部配置参数与高级场景：查阅 [配置与使用指南](/docs/configuration)
- 遇到问题排错：查阅 [常见问题与排错](/docs/faq)
