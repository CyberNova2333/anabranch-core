#!/bin/bash
# 组装 anabranch-aria2 的 AAR。AAR 本质是个 zip，不需要 Android Gradle
# Plugin——布局：AndroidManifest.xml + classes.jar + jni/<abi>/*.so + R.txt。
#
# classes.jar 只装 io.anabranch.aria2.*：anabranch-core 是独立依赖，把它的
# 类打进来会让使用者 classpath 里出现两份 core（铁律 4 的 artifact 边界）。
# THIRD-PARTY-NOTICES.md 打进 AAR 根——GPL 义务随产物走，不靠人记得附带。
#
# 用法: bash aria2/native/package-aar.sh <so目录> <输出.aar>
#   <so目录> 布局: <abi>/libaria2-{openssl,gnutls}.so（build-aria2.sh 的 out/）
set -euo pipefail

SODIR="${1:?用法: package-aar.sh <so目录> <输出.aar>}"
OUT="${2:?用法: package-aar.sh <so目录> <输出.aar>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
case "$OUT" in /*) ;; *) OUT="$(pwd)/$OUT" ;; esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "── 编译 classes.jar（--release 8，与 .so 同一套 minSdk 21 纪律）"
javac --release 8 -nowarn -d "$work/classes" \
    "$ROOT"/src/io/anabranch/*.java \
    "$ROOT"/aria2/src/io/anabranch/aria2/*.java
mkdir -p "$work/aar"
( cd "$work/classes" && jar cf "$work/aar/classes.jar" io/anabranch/aria2 )

echo "── 装入 .so"
for abi in arm64-v8a armeabi-v7a; do
    mkdir -p "$work/aar/jni/$abi"
    for be in openssl gnutls; do
        so="$SODIR/$abi/libaria2-$be.so"
        [ -f "$so" ] || { echo "✗ 缺 $so"; exit 1; }
        cp "$so" "$work/aar/jni/$abi/"
    done
done

cat > "$work/aar/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.anabranch.aria2">
  <uses-sdk android:minSdkVersion="21" />
</manifest>
EOF
: > "$work/aar/R.txt"
cp "$ROOT/aria2/THIRD-PARTY-NOTICES.md" "$work/aar/"

rm -f "$OUT"
( cd "$work/aar" && zip -qr "$OUT" . )
echo "✓ $OUT ($(stat -c%s "$OUT") bytes)"
sha256sum "$OUT"
