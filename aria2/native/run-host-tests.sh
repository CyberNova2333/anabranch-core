#!/bin/sh
# Aria2Downloader 的真下载测试：宿主构建 libaria2（增量，首次约几分钟）→
# 编译 core + aria2 + 测试 → 载入真库对着本地 HTTP 服务器跑。
#
# 刻意不并进 tools/run-checks.sh：那条命令的承诺是「javac + python3 就够、
# 一分钟出结果」，而这里要 C++ 工具链和一次 aria2 编译。CI 里两者是并行的
# 两个 job，本地改了 aria2 相关代码就手动跑这条。
set -e
cd "$(git rev-parse --show-toplevel)"

bash aria2/native/build-host.sh

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

echo "── 编译（core + aria2 + 测试）──"
javac -nowarn -d "$out" src/io/anabranch/*.java aria2/src/io/anabranch/aria2/*.java \
    aria2/test/*.java

echo "── 真下载测试（载入宿主 libaria2）──"
java -Dstdout.encoding=UTF-8 -Djava.library.path=aria2/out-host -cp "$out" Aria2DownloaderTest

echo "── aria2 后端测试通过 ──"
