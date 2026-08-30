# -*- coding: utf-8 -*-
"""commit-msg 与 pre-push 共用的提交信息判据。

判据只有一份、两个钩子都用它，因为它们查的是同一件事、在两个不同的时刻：

  commit-msg  你在**装了钩子的克隆里**提交时；
  pre-push    你**往远端推**时——这道才是真正兜底的。

为什么需要第二道：commit-msg 只管得住「提交这个动作发生在这份克隆里」。在别处
产生、然后作为分支推进来的提交，commit-msg 从头到尾没有机会运行。只有第一道的话，
这套东西挡不住最容易发生的那种绕过。
"""

import re

CJK = re.compile(r"[一-鿿]")
# 顶格独占一行才算：`git commit -v` 的 diff 上下文行正是「一个空格 + 原文」，
# 而文档里又确实会引用这个标记本身。
SKIP_LINE = re.compile(r"^\[skip-hooks\][ \t]*$", re.M)
# git commit -v 的剪刀线，其后是 diff，不参与判定
SCISSORS = re.compile(r"^#?\s*-+\s*>8\s*-+", re.M)
# git 自己生成的信息，不强求
AUTO_PREFIXES = ("Merge ", "Revert ", "fixup!", "squash!")

TYPES = ("feat", "fix", "refactor", "docs", "test", "chore", "ci", "perf", "build")
PREFIX = re.compile(r"^(?:" + "|".join(TYPES) + r")(?:\([A-Za-z0-9_./-]+\))?!?:\s*")
LIKE_PREFIX = re.compile(r"^([A-Za-z]+)(?:\([^)]*\))?!?:")


def normalize(raw, drop_comments):
    """返回 (subject, body)。

    drop_comments 只在 commit-msg 侧为真——那里读的是编辑器缓冲，`#` 开头的行是
    git 加的说明。pre-push 侧读的是 `git log %B`，已经是成品，此时再删 `#` 开头
    的行反而会改坏正文（正文里的 Markdown 标题就以 # 开头）。
    """
    m = SCISSORS.search(raw)
    if m:
        raw = raw[:m.start()]
    lines = raw.split("\n")
    if drop_comments:
        lines = [l for l in lines if not l.startswith("#")]
    subject = ""
    for l in lines:
        if l.strip():
            subject = l.strip()
            break
    return subject, "\n".join(lines)


def problems(raw, drop_comments=False):
    """返回不合规之处的列表；合规返回空列表。"""
    subject, body = normalize(raw, drop_comments)

    if subject.startswith(AUTO_PREFIXES):
        return []
    if SKIP_LINE.search(body):
        return []

    out = []
    if not subject:
        out.append("提交信息是空的")
    else:
        m = PREFIX.match(subject)
        if not m:
            like = LIKE_PREFIX.match(subject)
            if like:
                out.append("Conventional 类型不在允许表内: " + like.group(1) + "\n"
                           "      允许的 type: " + " ".join(TYPES) + "\n"
                           "      当前标题: " + subject[:72])
            else:
                out.append("标题必须是「type(scope): 中文描述」（AGENTS.md §1 一）\n"
                           "      允许的 type: " + " ".join(TYPES) + "\n"
                           "      例如: fix(engine): 修正 Range 被忽略时的误判\n"
                           "      当前标题: " + subject[:72])
        elif not CJK.search(subject[m.end():]):
            out.append("前缀之后的描述必须用**中文**（AGENTS.md §1 一）\n"
                       "      当前标题: " + subject[:72])

    if not re.search(r"^Co-authored-by:\s*\S[^<>\n]*<[^<>\s]+@[^<>\s]+>", body, re.M):
        out.append("缺（或格式不对的）Co-authored-by trailer（AGENTS.md §1 三）\n"
                   "      必须是完整的 Name <email> 形式，末尾加一行，例如:\n"
                   "        Co-authored-by: Claude <noreply@anthropic.com>\n"
                   "      「Co-authored-by: x」这种半截写法不算数。")

    if not re.search(r"^文档[:：]", body, re.M):
        out.append("缺「文档:」交代（AGENTS.md §1 四）\n"
                   "      写明对应文档改动，或明确写不影响，例如:\n"
                   "        文档: 已更新 README.md 的「选路」一节\n"
                   "        文档: 纯内部重构，不影响任何文档描述")

    return out
