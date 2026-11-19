#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# This file is part of Anabranch Core and is licensed under GNU AGPLv3 with Additional Terms (see LICENSE and LICENSE-ADDITIONAL_TERMS-zh-CN.md; ref: LICENSE-ADDITIONAL_TERMS-en.md).
# 把这份克隆的 git 钩子指到受版本控制的 tools/githooks/。
#
# 装了本仓库的 Agent（见 .claude/settings.json）跑过任意一条 Bash 命令之后会自动
# 做这件事，所以只有「Agent 一次都没碰过这份克隆」时才需要手动跑一次。
set -e
root=$(git rev-parse --show-toplevel)
git -C "$root" config core.hooksPath tools/githooks
echo "✔ core.hooksPath → tools/githooks（当前克隆生效）"
