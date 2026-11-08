---
title: 配置与使用指南
description: Anabranch Core 的参数配置详解、典型使用场景与最佳实践
---

# 配置与使用指南

### 配置文件 (.git/dual-push.json)

每个纳管的 Git 仓库根目录下拥有独立的同步状态描述文件：

```json
{
  "github_remote": "github",
  "cnb_remote": "origin",
  "branches": {
    "main": {
      "github": "1f7bd13b501f102e4368b2cd1ee02d868244f6b7",
      "cnb": "2f1f8958cf49473d6aa61b474b018f15580b07ad",
      "mapping": {
        "2f1f8958cf49473d6aa61b474b018f15580b07ad": "1f7bd13b501f102e4368b2cd1ee02d868244f6b7"
      }
    }
  }
}
```

### 密钥与身份隔离规范

- **GitHub 主平台**：作者与提交者邮箱固定为 GitHub noreply 邮箱，强制使用 `id_ed25519_github_signing` 签名；
- **备用冷备镜像**：由引擎在底层使用 `commit-tree` 自动重签，映射为专用冷备 noreply 邮箱与 `id_ed25519_cnb_signing` 密钥，绝不交叉混用。

---

## 生产部署建议

1. **环境变量保护**：切勿将包含真实密钥或 Token 的配置提交至版本库；
2. **优雅停机与超时**：建议将关键长任务的超时时限设置为合理范围，避免僵尸进程驻留；
3. **日志监控**：结合控制台输出建立定期检查机制，捕获潜在异常。
