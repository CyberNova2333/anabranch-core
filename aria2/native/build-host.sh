#!/bin/bash
# 宿主（桌面 Linux）构建 libaria2-openssl.so——只为让 Aria2Downloader 的
# **真下载测试**能在桌面 JVM 上跑：同一份 JNI 胶水、同一版 aria2（1.37.0），
# 对着真实的本地 HTTP 服务器传真字节。发货产物仍然只出自 build-aria2.sh
# （Android 交叉编译），本脚本产物不入库、不分发。
#
# 与 Android 版的差异（都只减不改）：单后端（openssl，测试用不到双后端）、
# 依赖面裁到系统 openssl + zlib（无 xml2/sqlite/ssh2，metalink/SFTP 测试无关）、
# 无 16K 对齐与 stub 校验（那是 Android 装载约束）。version script 与符号
# 卫生保持一致——测试载入的库和发货的库在接口面上必须是同一个东西。
#
# 需要的系统包：g++ make pkg-config libssl-dev zlib1g-dev（外加 JDK）。
# 产物：aria2/out-host/libaria2-openssl.so
set -euo pipefail

GLUE_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$GLUE_DIR/../out-host"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
SO="$WORK/libaria2-openssl.so"
NPROC=$(nproc)

# 增量：aria2 静态库编好就不重编（十几分钟 vs 秒级）；胶水改动只需重链。
if [ -f "$SO" ] \
   && [ "$SO" -nt "$GLUE_DIR/aria2_jni.cpp" ] \
   && [ "$SO" -nt "$GLUE_DIR/libaria2-anabranch.map" ] \
   && [ "$SO" -nt "$GLUE_DIR/build-host.sh" ]; then
    echo "✓ 已是最新：$SO"
    exit 0
fi

cd "$WORK"

if [ ! -d aria2-src ]; then
    echo "── 下载 aria2 1.37.0（与 Android 构建同一 sha256 pin）"
    curl -fsSL --retry 3 -o aria2.tar.gz \
        https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0.tar.gz
    echo "8e7021c6d5e8f8240c9cc19482e0c8589540836747744724d86bf8af5a21f0e8  aria2.tar.gz" | sha256sum -c -
    tar xzf aria2.tar.gz && mv aria2-1.37.0 aria2-src
fi

if [ ! -f aria2-build/src/.libs/libaria2.a ]; then
    echo "── configure + make（宿主，--enable-libaria2）"
    mkdir -p aria2-build
    ( cd aria2-build && \
      ../aria2-src/configure --enable-libaria2 --enable-static --disable-shared \
          --with-openssl --without-gnutls --without-libnettle --without-libgmp \
          --without-libxml2 --without-libexpat --without-sqlite3 --without-libssh2 \
          --without-libcares --without-libuv \
          --without-jemalloc --without-tcmalloc --without-appletls \
          --without-wintls --without-libgcrypt \
          CFLAGS="-O2 -fPIC -w" CXXFLAGS="-O2 -fPIC -w" \
          > configure.log 2>&1 ) \
        || { echo "✗ configure 失败"; tail -40 aria2-build/configure.log; exit 1; }
    make -C aria2-build -j"$NPROC" > aria2-build/make.log 2>&1 \
        || { echo "✗ make 失败"; tail -40 aria2-build/make.log; exit 1; }
fi

JAVAC="$(readlink -f "$(command -v javac)")"
JH="$(dirname "$(dirname "$JAVAC")")"
[ -f "$JH/include/jni.h" ] || { echo "✗ 找不到 jni.h（JAVA_HOME=$JH）"; exit 1; }

WSLAY="aria2-build/deps/wslay/lib/.libs/libwslay.a"
[ -f "$WSLAY" ] || { echo "✗ 缺 wslay 静态库"; exit 1; }

echo "── 链接 $SO"
# 与 Android 版同一套符号纪律：hidden + version script + -Bsymbolic +
# --no-undefined。TLS/zlib 在宿主上动态链系统库即可——测试进程里没有第二份
# libssl 来抢符号，且 version script 仍保证本库只导出 9 个 JNI 入口。
g++ -shared -O2 -fPIC -std=c++17 \
    -I"$WORK/aria2-src/src/includes" \
    -I"$JH/include" -I"$JH/include/linux" \
    -fvisibility=hidden \
    "$GLUE_DIR/aria2_jni.cpp" \
    aria2-build/src/.libs/libaria2.a "$WSLAY" \
    -Wl,--version-script="$GLUE_DIR/libaria2-anabranch.map" -Wl,-Bsymbolic \
    -Wl,--no-undefined \
    -lssl -lcrypto -lz -lpthread -ldl \
    -o "$SO"

# 自检（宿主版）：导出符号恰 9 个——测试载入前先确认接口面没走样。
# GNU nm 会把版本节点本身当 ABS 符号（类型 A）列出来，llvm-nm 不会——滤掉。
exports="$(nm -D --defined-only "$SO" | awk '$2!="A"{print $NF}' | grep -v '^$' | sed 's/@@.*//' | sort)"
expect="$(printf '%s\n' \
    JNI_OnLoad \
    Java_io_anabranch_aria2_Aria2Native_sessionNew \
    Java_io_anabranch_aria2_Aria2Native_sessionShutdown \
    Java_io_anabranch_aria2_Aria2Native_sessionRelease \
    Java_io_anabranch_aria2_Aria2Native_addUri \
    Java_io_anabranch_aria2_Aria2Native_runOnce \
    Java_io_anabranch_aria2_Aria2Native_progressOf \
    Java_io_anabranch_aria2_Aria2Native_statusOf \
    Java_io_anabranch_aria2_Aria2Native_errorOf | sort)"
[ "$exports" = "$expect" ] || { echo "✗ 导出符号异常:"; diff <(echo "$expect") <(echo "$exports") || true; exit 1; }

echo "✓ $SO ($(stat -c%s "$SO") bytes)"
