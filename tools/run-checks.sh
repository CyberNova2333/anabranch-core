#!/bin/sh
# 提交前跑这一条就够了：编译 → core 边界 → 两套测试。
#
# 「javac 过了」不等于「能提交」——这个库的正确性几乎全在测试里，而测试是真起
# 本地 HTTP 服务器真下文件的，编译器一点忙都帮不上。
set -e
cd "$(git rev-parse --show-toplevel)"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

echo "── core 按 Java 8 编译 ──"
# 这条比任何静态检查都强：minSdk 21 的实际含义就是「core 的字节码必须是 Java 8
# 能吃下的」。--release 8 会连**类库**一起限制住（不只是语法），所以误用
# java.util.function 这类 API 24+ 才有的东西会在这里当场编译失败。
# tools/check-core-boundaries.py 仍然有用——它管的是 android.*、第三方依赖与分层，
# 那三条 --release 看不见。
javac --release 8 -nowarn -d "$out/core8" src/io/anabranch/*.java

echo "── 编译（含测试）──"
javac -nowarn -d "$out" src/io/anabranch/*.java test/*.java

echo "── core 边界 ──"
python3 tools/check-core-boundaries.py

echo "── 测试 ──"
java -Dstdout.encoding=UTF-8 -cp "$out" SingleSourceTest
java -Dstdout.encoding=UTF-8 -cp "$out" MultiSourceTest
java -Dstdout.encoding=UTF-8 -cp "$out" ThrottleTest

echo "── 全部通过 ──"
