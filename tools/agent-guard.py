#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PreToolUse 钩子（matcher: Bash）—— 让 tools/githooks/ 里的 git 钩子自动生效。

git 自己的钩子没法从入库文件里自动生效：`core.hooksPath` 是每份克隆的本地配置，
这是 git 有意为之的安全设计——否则 clone 一个仓库就等于执行任意代码。所以本来
需要人手跑一次 tools/install-hooks.sh。

本脚本把那一步消掉：Agent 侧的 PreToolUse 在命令**执行之前**跑，这里把
core.hooksPath 指好之后，紧接着那条 git commit / git push 就已经走 tools/githooks/。
也就是说，Agent 跑过任意一条 Bash 命令，这份克隆的 git 钩子从此长期生效，
之后连人类手敲的 commit 也一并受管。

逻辑不重复实现——真正的判定仍然只在 tools/githooks/ 里，本脚本只负责接上电。

## 唯一在这里直接拦的事：绕过 git 钩子

git 钩子唯一挡不住的就是绕过 git 钩子本身：

  · git commit --no-verify / -n、git push --no-verify
  · git -c core.hooksPath=... commit/push（把钩子目录换掉，更隐蔽）

**这不是安全边界**，是防手滑与防偷懒的护栏。真想绕总归绕得过去。判定的目标是
「常见写法一个不漏、正常提交一个不误伤」，不是堵死一切。

用 shlex 分词而不是在原始命令文本上跑正则：正则那版同时会漏和会误伤——
`git -c user.name=x commit -n` 漏（git 与 commit 之间隔了全局选项），而
`git commit -m "调整 -n 参数的说明"` 却被拦下。
"""

import json
import os
import shlex
import subprocess
import sys

# 中文 Windows 下 Python 默认按 GBK 读写标准流，而 Claude Code 收发的都是 UTF-8：
# 不统一的话，命令里一有中文就解析失败、落进下面的放行分支。
for _s in (sys.stdin, sys.stdout, sys.stderr):
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")

ESCAPES = "文档里写明的逃生口：提交信息顶格独占一行 [skip-hooks]，或 SKIP_MSG_HOOK=1 git push"


def repo_root():
    d = os.environ.get("CLAUDE_PROJECT_DIR")
    if d and os.path.isdir(d):
        return d
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace")
        return out.stdout.strip() if out.returncode == 0 else ""
    except OSError:
        return ""


def wire_hooks(root):
    hooks = os.path.join(root, "tools", "githooks")
    if not os.path.isdir(hooks):
        return
    try:
        cur = subprocess.run(["git", "-C", root, "config", "--get", "core.hooksPath"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()
        if cur != "tools/githooks":
            subprocess.run(["git", "-C", root, "config", "core.hooksPath",
                            "tools/githooks"], capture_output=True)
    except OSError:
        pass


def bypasses_hooks(cmd):
    """命令里有没有在绕过钩子。返回原因字符串，没有则返回 None。"""
    try:
        toks = shlex.split(cmd)
    except ValueError:
        return None                      # 引号都不配对，不是我们该管的事
    i = 0
    while i < len(toks):
        if toks[i] != "git":
            i += 1
            continue
        j = i + 1
        # 跳过 git 的全局选项；-c k=v 可能分成两个 token
        while j < len(toks) and toks[j].startswith("-"):
            if toks[j] == "-c" and j + 1 < len(toks):
                if toks[j + 1].startswith("core.hooksPath="):
                    return "git -c core.hooksPath=…（换掉钩子目录）"
                j += 2
                continue
            if toks[j].startswith("-c") and "core.hooksPath=" in toks[j]:
                return "git -c core.hooksPath=…（换掉钩子目录）"
            j += 1
        if j < len(toks) and toks[j] in ("commit", "push", "merge"):
            rest = toks[j + 1:]
            for t in rest:
                if t == "--no-verify" or t == "-n" or t.startswith("--no-verify="):
                    return "git %s %s" % (toks[j], t)
            i = j + 1
            continue
        i = j + 1 if j > i else i + 1
    return None


def main():
    root = repo_root()
    if root:
        wire_hooks(root)

    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8", "replace"))
    except Exception:
        return 0
    cmd = (payload.get("tool_input") or {}).get("command") or ""
    why = bypasses_hooks(cmd)
    if why:
        sys.stderr.write("✘ 拦下：%s\n  %s\n" % (why, ESCAPES))
        return 2                          # 2 = 阻断并把 stderr 回灌给模型
    return 0


if __name__ == "__main__":
    sys.exit(main())
