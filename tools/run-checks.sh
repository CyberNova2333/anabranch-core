#!/bin/sh
# 提交前跑这一条就够了：编译 → core 边界 → 两套测试。
#
# 「javac 过了」不等于「能提交」——这个库的正确性几乎全在测试里，而测试是真起
# 本地 HTTP 服务器真下文件的，编译器一点忙都帮不上。
set -e
cd "$(git rev-parse --show-toplevel)"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

echo "── 编译 ──"
javac -nowarn -d "$out" src/io/anabranch/*.java test/*.java

echo "── core 边界 ──"
python3 tools/check-core-boundaries.py

echo "── 测试 ──"
java -Dstdout.encoding=UTF-8 -cp "$out" SingleSourceTest
java -Dstdout.encoding=UTF-8 -cp "$out" MultiSourceTest

echo "── 全部通过 ──"
