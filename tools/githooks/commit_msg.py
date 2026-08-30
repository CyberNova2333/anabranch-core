# -*- coding: utf-8 -*-
"""commit-msg 钩子的实现。判据在 _msgrules.py。"""

import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _msgrules                                              # noqa: E402


def main(argv):
    if len(argv) < 2:
        return 0                       # 拿不到信息文件就别拦，不是提交者的错
    try:
        with io.open(argv[1], encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError:
        return 0

    bad = _msgrules.problems(raw, drop_comments=True)
    if not bad:
        return 0

    sys.stderr.write("\n✘ 提交信息不合规（%d 处）：\n\n" % len(bad))
    for b in bad:
        sys.stderr.write("  · " + b + "\n")
    sys.stderr.write(
        "\n  规则见 AGENTS.md §1。确需跳过时，在提交信息里**顶格独占一行**写：\n"
        "    [skip-hooks]\n\n")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
