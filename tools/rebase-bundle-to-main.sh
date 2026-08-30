#!/usr/bin/env bash
# ============================================================================
# rebase-bundle-to-main.sh — 把产出的 git bundle 安全地接到 origin/main 上
# ============================================================================
#
# 目的：
#   anabranch-core 的日常流程是「另起机器/Agent 产出 commit → 打成 bundle →
#   这里导入并推送」。生成端往往用非本机签名 key（例如 Claude GitHub App 的
#   key）签了名，导致 GitHub 显示 Unverified；有些 bundle 还基于旧的历史链，
#   与 origin/main 分叉。
#
#   本脚本把 bundle 当作「下一条 commit」处理，而不是原样推送：
#   1. 始终以当前已验证的 origin/main 为基线（不覆盖、保证主线单一、fast-forward）；
#   2. 用本机 user.signingkey 重新签名，使 GitHub 可校验（Verified）；
#   3. push 用 --force-with-lease 防竞态；push 前本地验签 + tree 一致性检查。
#
# 兼容：
#   - 完整 bundle（含全部历史，如 anabranch-step1.bundle）
#   - 增量/thin bundle（只含 delta + 前置依赖 prerequisite，如 anabranch-ci.bundle）
#   - 对齐（tip 是 origin/main 的子代，只需重签）与分叉（需 cherry-pick）两种基线
#
# 用法：
#   tools/rebase-bundle-to-main.sh <bundle文件> [--push]
#     [--push]   实际推送；缺省为 dry-run，只做检查与本地重签，不推。
#
# 要求（本机已具备）：
#   - git config user.signingkey 指向一把 SSH 签名 key（gpg.format=ssh）
#   - gh 已登录且该 signing key 已注册为 GitHub 账号的 SSH signing key
#   - 账号能以 https push
#
# 退出码：0 成功；非 0 失败（脚本不会把 main 弄坏；失败时 origin 未被改动）。
# ============================================================================
set -euo pipefail

BUNDLE="${1:?用法: rebase-bundle-to-main.sh <bundle文件> [--push]}"
PUSH="${2:-}"

REPO_URL="$(git remote get-url origin 2>/dev/null || true)"
[ -n "$REPO_URL" ] || REPO_URL="https://github.com/CyberNova2333/anabranch-core.git"

SIGNKEY="$(git config --get user.signingkey || true)"
[ -n "$SIGNKEY" ] || { echo "错误: 未配置 user.signingkey（SSH 签名 key）"; exit 1; }
[ -f "$SIGNKEY" ] || { echo "错误: signingkey 文件不存在: $SIGNKEY"; exit 1; }

# SSH 签名的身份主词（principal）——allowedSigners 主词取全局 user.email + "git"
# 两个候选，避免身份字符串细节差异导致 No principal matched。
SIGN_PUB="$SIGNKEY"
if [ -f "${SIGN_PUB%.pub}" ]; then
  PRIV_KEY="${SIGN_PUB%.pub}"
else
  PRIV_KEY="$SIGN_PUB"
fi
EMPRINCIPAL="$(git config --get user.email || true)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
git init -q
git remote add origin "$REPO_URL"

echo "▸ 拉取 origin/main 以获取当前已验证基线"
git fetch -q origin
ORIG_HEAD="$(git rev-parse refs/remotes/origin/main)"
echo "  origin/main = $ORIG_HEAD"

echo "▸ 解析 bundle（完整或增量均可）"
# 增量 bundle 的前置依赖须已存在；origin 拉取通常已满足。
git fetch -q "$BUNDLE" "refs/heads/main:refs/bundle/incoming"
INCOMING="$(git rev-parse refs/bundle/incoming)"
echo "  bundle tip = $INCOMING"
echo "  message    = $(git log -1 --format='%s' "$INCOMING")"

# 校验：先按 bundle 的 author 身份设 committer（author 会被 amend/cherry-pick 保留）
AUTHOR_NAME="$(git log -1 --format='%an' "$INCOMING")"
AUTHOR_EMAIL="$(git log -1 --format='%ae' "$INCOMING")"
git config user.name "$AUTHOR_NAME"
git config user.email "$AUTHOR_EMAIL"

# 本地验签用的 allowedSigners：把签名 key 挂在多个候选主词下
cat > "$WORK/allowed_signers" <<EOF
$EMPRINCIPAL $(awk '{print $1, $2}' "$SIGN_PUB")
$AUTHOR_EMAIL $(awk '{print $1, $2}' "$SIGN_PUB")
git $(awk '{print $1, $2}' "$SIGN_PUB")
EOF

verify_local() {
  git -c gpg.ssh.allowedSignersFile="$WORK/allowed_signers" verify-commit "$1" >/dev/null 2>&1 \
    && echo "  本地验签 OK" || { echo "  本地验签失败"; return 1; }
}

git checkout -q -b step origin/main

# 判断对齐(o 是子代) / 分叉
if git merge-base --is-ancestor origin/main "$INCOMING"; then
  echo "▸ 基线对齐：bundle tip 是 origin/main 的子代 → 直接重签"
  git reset -q --hard "$INCOMING"
  git commit -q --amend --no-edit -S
else
  echo "▸ 基线分叉：bundle tip 不在 origin/main 之上 → cherry-pick 后重签"
  git cherry-pick "$INCOMING" >/dev/null 2>&1 || {
    echo "  cherry-pick 冲突，已放弃（未改动 origin）。请人工处理冲突后重跑。"; exit 1; }
  git commit -q --amend --no-edit -S
fi

NEW="$(git rev-parse HEAD)"
echo "  新 commit = $NEW"

# 安全校验：产物 tree 必须与 bundle tip 完全一致（内容不丢）
BUNDLE_TREE="$(git rev-parse "$INCOMING^{tree}")"
NEW_TREE="$(git rev-parse "$NEW^{tree}")"
if [ "$BUNDLE_TREE" != "$NEW_TREE" ]; then
  echo "错误: tree 不一致（$BUNDLE_TREE != $NEW_TREE），放弃。origin 未被改动。"; exit 1
fi
echo "  tree 一致性 OK（$NEW_TREE）"

# 父提交必须就是 origin/main（保证 fast-forward）
if [ "$(git rev-parse "$NEW^")" != "$ORIG_HEAD" ]; then
  echo "错误: 产物父提交不为 origin/main，非 fast-forward，放弃。"; exit 1
fi
echo "  父提交校验 OK（= origin/main）"

verify_local "$NEW"

echo "──────────────────────────────────────────────"
echo "  ready: origin/main ($ORIG_HEAD) → $NEW  ($(git log -1 --format='%s' "$NEW"))"
echo "──────────────────────────────────────────────"

if [ "$PUSH" = "--push" ]; then
  echo "▸ 推送（force-with-lease 防止竞态覆盖）"
  git push --force-with-lease=refs/heads/main:"$ORIG_HEAD" origin "$NEW:refs/heads/main"
  echo "  push 完成"
else
  echo "▸ dry-run 模式，未推送。加 --push 以实际推送。"
fi
