#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CI 侧的提交信息检查：查一个提交区间，判据与 git 钩子共用。

## 为什么本地有钩子了还要这一道

本地那两道钩子挡得住绝大多数情况，但都有明确的漏口：

  · `commit-msg` 只在**装了钩子的克隆里**生效；
  · `pre-push` 有 `SKIP_MSG_HOOK=1` 这个逃生口。

CI 是最后一道，它跑在服务端、没有本地配置可绕。这样一来 `SKIP_MSG_HOOK=1` 的
真实语义就清楚了：**「先让我推上去，CI 那边我认」**，而不是「这条不用守规矩」。

真要留一条不合规的信息在历史里，正规做法是在信息里顶格独占一行写 `[skip-hooks]`
——那是记录在案的、看得见的豁免，本脚本与钩子一视同仁地认它。

用法：

    python3 tools/check-commit-messages.py <base>..<head>
    python3 tools/check-commit-messages.py            # 默认 origin/main..HEAD
"""

import os
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "githooks"))
import _msgrules                                              # noqa: E402


def sh(args):
    p = subprocess.run(args, capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else ""


def main(argv):
    rng = argv[1] if len(argv) > 1 else "origin/main..HEAD"
    out = sh(["git", "rev-list", "--no-merges", rng])
    shas = [l.strip() for l in out.split("\n") if l.strip()]
    if not shas:
        print("✔ 区间 %s 内没有需要检查的提交" % rng)
        return 0

    bad = 0
    for sha in shas:
        msg = sh(["git", "log", "-1", "--format=%B", sha])
        probs = _msgrules.problems(msg, drop_comments=False)
        subject = msg.split("\n")[0][:72]
        if probs:
            bad += 1
            print("\n✘ %s  %s" % (sha[:9], subject))
            for b in probs:
                print("  · " + b)
        else:
            print("✔ %s  %s" % (sha[:9], subject))

    if bad:
        print("\n共 %d / %d 个提交的信息不合规（规则见 AGENTS.md §1）。" % (bad, len(shas)))
        print("要留下不合规的信息，请在信息里**顶格独占一行**写 [skip-hooks]——")
        print("那是记录在案的豁免；SKIP_MSG_HOOK=1 只跳过本地检查，不跳过这一道。")
        return 1
    print("\n✔ %d 个提交的信息全部合规" % len(shas))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
