# -*- coding: utf-8 -*-
"""pre-push 钩子：检查**本次推送新增**的提交信息。

为什么需要这一道：commit-msg 只管得住「提交这个动作发生在这份克隆里」。在别处
产生、然后作为分支推进来的提交，commit-msg 从头到尾没有机会运行。

只查 `remote..local` 这一段，**历史不翻旧账**——对已经在远端的提交报错既没意义
（改不了）又会让人学会无脑加逃生口。

逃生口：`SKIP_MSG_HOOK=1 git push`。
"""

import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _msgrules                                              # noqa: E402

ZERO = "0" * 40


def run(args):
    p = subprocess.run(args, capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else ""


def new_commits(local_sha, remote_sha):
    """本次推送会新增哪些提交。新建远端分支时用 --not --remotes 兜底。"""
    if remote_sha == ZERO or not remote_sha:
        out = run(["git", "rev-list", local_sha, "--not", "--remotes", "--max-count=200"])
    else:
        out = run(["git", "rev-list", "%s..%s" % (remote_sha, local_sha), "--max-count=200"])
    return [l.strip() for l in out.split("\n") if l.strip()]


def main():
    if os.environ.get("SKIP_MSG_HOOK"):
        sys.stderr.write("提示：SKIP_MSG_HOOK=1，跳过提交信息检查。\n")
        return 0

    bad_total = 0
    for line in sys.stdin:
        parts = line.split()
        if len(parts) < 4:
            continue
        _local_ref, local_sha, _remote_ref, remote_sha = parts[:4]
        if local_sha == ZERO:
            continue                                  # 删分支，没有新提交
        for sha in new_commits(local_sha, remote_sha):
            msg = run(["git", "log", "-1", "--format=%B", sha])
            bad = _msgrules.problems(msg, drop_comments=False)
            if bad:
                bad_total += 1
                subject = msg.split("\n")[0][:72]
                sys.stderr.write("\n✘ %s  %s\n" % (sha[:9], subject))
                for b in bad:
                    sys.stderr.write("  · " + b + "\n")

    if bad_total:
        sys.stderr.write(
            "\n  共 %d 个提交的信息不合规（规则见 AGENTS.md §1）。\n"
            "  改历史：git rebase -i --exec 'git commit --amend' <base>\n"
            "  确需跳过：SKIP_MSG_HOOK=1 git push\n\n" % bad_total)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
