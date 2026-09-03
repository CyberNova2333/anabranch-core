#!/bin/bash
# 从 pinned 的上游源码里**原样**抽出各组件的许可证与版权声明，供打进 AAR
# （META-INF/licenses/）。GPL 要求随分发附 License 副本；OpenSSL 旧许可、BSD、
# MIT 都要求二进制分发复制版权与许可声明——只有我们自己写的 NOTICES 汇总表
# 不算数，原文必须在。
#
# 用法（与 build-aria2.sh 同一组环境变量，由 workflow 导出）：
#   ARIA2_SRC=... OPENSSL_SRC=... GMP_SRC=... NETTLE_SRC=... GNUTLS_SRC=... \
#   XML2_SRC=... SQLITE_SRC=... SSH2_SRC=... ZLIB_SRC=... [ARIA2_SLIM=1] \
#   bash aria2/native/collect-licenses.sh <输出目录>
#
# 缺任何一份就失败：宁可发不出去，也不要发出一份少了声明的二进制。
set -euo pipefail

OUT="${1:?用法: collect-licenses.sh <输出目录>}"
for v in ARIA2_SRC OPENSSL_SRC GMP_SRC NETTLE_SRC GNUTLS_SRC ZLIB_SRC; do
    [ -d "${!v:-}" ] || { echo "✗ 请设置 $v 指向已解压源码目录"; exit 1; }
done
if [ -z "${ARIA2_SLIM:-}" ]; then
    for v in XML2_SRC SQLITE_SRC SSH2_SRC; do
        [ -d "${!v:-}" ] || { echo "✗ 请设置 $v 指向已解压源码目录（全量构建需要）"; exit 1; }
    done
fi
rm -rf "$OUT" && mkdir -p "$OUT"

take() { # take <组件目录> <源文件> [目标文件名]
    local comp="$1" src="$2" name="${3:-$(basename "$2")}"
    [ -f "$src" ] || { echo "✗ 缺许可文件：$src"; exit 1; }
    mkdir -p "$OUT/$comp"
    cp "$src" "$OUT/$comp/$name"
}

# 组合体整体按 GPL-3.0-or-later 分发——附 GPLv3 全文（取自 GMP 包，规范文本）
take . "$GMP_SRC/COPYINGv3" GPL-3.0.txt

take aria2   "$ARIA2_SRC/COPYING"                 # GPLv2 文本 + aria2 版权
take aria2   "$ARIA2_SRC/LICENSE.OpenSSL"         # aria2 的 OpenSSL 链接例外
take wslay   "$ARIA2_SRC/deps/wslay/COPYING"      # MIT
take OpenSSL "$OPENSSL_SRC/LICENSE"               # OpenSSL / SSLeay 双许可（要求二进制分发附版权声明）
take GnuTLS  "$GNUTLS_SRC/LICENSE"
take GnuTLS  "$GNUTLS_SRC/doc/COPYING.LESSER"     # LGPL-2.1
take nettle  "$NETTLE_SRC/COPYING.LESSERv3"
take nettle  "$NETTLE_SRC/COPYINGv2"
take GMP     "$GMP_SRC/COPYING.LESSERv3"
take GMP     "$GMP_SRC/COPYINGv2"
take zlib    "$ZLIB_SRC/LICENSE"

if [ -z "${ARIA2_SLIM:-}" ]; then
    take libxml2 "$XML2_SRC/Copyright"            # MIT
    take libssh2 "$SSH2_SRC/COPYING"              # BSD-3-Clause（要求二进制分发附版权声明）
    # SQLite 的 autoconf 包不带许可文件：它是公有领域，附 sqlite.org 的声明要点。
    mkdir -p "$OUT/SQLite"
    cat > "$OUT/SQLite/PUBLIC-DOMAIN.txt" <<'TXT'
SQLite (https://sqlite.org/copyright.html)

All of the code and documentation in SQLite has been dedicated to the public
domain by the authors. All code authors, and representatives of the companies
they work for, have signed affidavits dedicating their contributions to the
public domain and originals of those signed affidavits are stored in a firesafe
at the main offices of Hwaci. Anyone is free to copy, modify, publish, use,
compile, sell, or distribute the original SQLite code, either in source code
form or as a compiled binary, for any purpose, commercial or non-commercial,
and by any means.
TXT
fi

{
    echo "anabranch-aria2 · 静态链接组件的许可证与版权声明原文"
    echo "组合体整体按 GPL-3.0-or-later 分发；组件清单与结论见 AAR 根目录的 THIRD-PARTY-NOTICES.md。"
    echo "构建面：$( [ -n "${ARIA2_SLIM:-}" ] && echo '裁剪（无 libxml2/SQLite/libssh2）' || echo '全量' )"
    echo
    ( cd "$OUT" && find . -type f ! -name INDEX.txt | sort | sed 's|^\./||' )
} > "$OUT/INDEX.txt"
echo "✓ 许可文件 $(find "$OUT" -type f | wc -l) 份 → $OUT"
