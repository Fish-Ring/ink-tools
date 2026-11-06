#!/usr/bin/env bash
# 免 Gradle / 免 AGP 的一键构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 只需要 Android SDK 的 platform-34 与 build-tools 34.0.0，不需要任何远程依赖。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVAC="${JAVAC:-javac}"

for f in "$PLATFORM" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
  if [ ! -f "$f" ]; then
    echo "缺少：$f"
    echo "请先安装 Android SDK platform-34 与 build-tools 34.0.0，或设置 ANDROID_HOME"
    exit 1
  fi
done

rm -rf build
mkdir -p build/classes build/dex build/gen

echo "[1/6] aapt2 compile 资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link 生成 APK 骨架"
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 \
  --java build/gen build/res.zip

echo "[3/6] javac 编译 Java"
find src -name '*.java' > build/sources.txt
"$JAVAC" --release 8 -encoding UTF-8 -classpath "$PLATFORM" \
  -d build/classes @build/sources.txt 2>build/javac.log || {
    echo "  --release 8 失败，回退到 -source/-target 8"
    "$JAVAC" -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
      -d build/classes @build/sources.txt
  }

echo "[4/6] d8 生成 dex"
# shellcheck disable=SC2046
"$BT/d8" --lib "$PLATFORM" --min-api 21 --output build/dex \
  $(find build/classes -name '*.class')

echo "[5/6] 打包 classes.dex"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)

echo "[6/6] 对齐并签名"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=EInk Toolbox, OU=Dev, O=Dev, L=CN, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out eink-toolbox.apk build/aligned.apk
"$BT/apksigner" verify eink-toolbox.apk

rm -f eink-toolbox.apk.idsig
SIZE=$(stat -c %s eink-toolbox.apk)
echo "完成：eink-toolbox.apk (${SIZE} bytes)"
