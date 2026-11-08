#!/usr/bin/env bash
# 免 Gradle / 免 AGP 的一键构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 只依赖 Android SDK 的 platform-34 与 build-tools，不需要任何远程依赖。
#
# 跨平台：自动识别 aapt2/d8/zipalign/apksigner 的 .exe / .bat 后缀；
# 没有 zip 命令时用 python 写入 classes.dex。
#
# JDK：默认用 17。build-tools 34 自带 d8(R8 8.2.2) 处理 JDK 21 编译出的 class
# 会抛 NullPointerException（不是代码问题），所以这里固定找 17。
#
# 配置优先级：环境变量 > local.properties > 常见安装路径。
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK 根目录
#   INK_JDK17                         JDK 17 根目录
#   BUILD_TOOLS                       build-tools 版本（默认 34.0.0）
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "✗ $*" >&2; echo; exit 1; }

# ---------------------------------------------------------------- 配置读取
prop() {  # prop <key>：读 local.properties，路径统一成 POSIX 风格
  [ -f local.properties ] || return 0
  grep -m1 "^$1=" local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r' | tr '\\' '/'
}

# ---------------------------------------------------------------- SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || SDK="$(prop sdk.dir)"
if [ -z "$SDK" ]; then
  for c in "$LOCALAPPDATA/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Android/Sdk" "/opt/android-sdk"; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
SDK="$(printf '%s' "$SDK" | tr '\\' '/')"
[ -n "$SDK" ] && [ -d "$SDK" ] || die "找不到 Android SDK。设置 ANDROID_HOME，或在 local.properties 写 sdk.dir=/路径"

PLATFORM="$SDK/platforms/android-34/android.jar"
[ -f "$PLATFORM" ] || die "缺少 $PLATFORM（需要 platforms;android-34）"

# build-tools：优先 34.0.0，没有就取最高的
BTDIR="$SDK/build-tools"
if [ -n "${BUILD_TOOLS:-}" ]; then
  BT="$BTDIR/$BUILD_TOOLS"
elif [ -d "$BTDIR/34.0.0" ]; then
  BT="$BTDIR/34.0.0"
else
  BT="$BTDIR/$(ls "$BTDIR" 2>/dev/null | sort -V | tail -1)"
fi
[ -d "$BT" ] || die "找不到 build-tools（$BTDIR 为空）"

tool() {  # tool <名字>：返回带后缀的完整路径
  for s in "" .exe .bat .cmd; do
    [ -f "$BT/$1$s" ] && { printf '%s' "$BT/$1$s"; return 0; }
  done
  return 1
}
AAPT2="$(tool aapt2)"        || die "$BT 里没有 aapt2"
D8="$(tool d8)"              || die "$BT 里没有 d8"
ZIPALIGN="$(tool zipalign)"  || die "$BT 里没有 zipalign"
APKSIGNER="$(tool apksigner)" || die "$BT 里没有 apksigner"

# ---------------------------------------------------------------- JDK 17
jdk_major() { "$1/bin/javac" -version 2>/dev/null | sed -n 's/javac \([0-9]*\).*/\1/p'; }

JDK="${INK_JDK17:-$(prop jdk17.dir)}"
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
  JDK=""
  for c in "$JAVA_HOME" \
           "$HOME/.jdks"/*17* \
           "$LOCALAPPDATA/Programs/Eclipse Adoptium"/*17* \
           "/c/Program Files/Java/jdk-17"* \
           "/d/Code/Environment/Java/jdk-17" \
           /usr/lib/jvm/java-17-*; do
    [ -n "$c" ] && [ -x "$c/bin/javac" ] && [ "$(jdk_major "$c")" = "17" ] && { JDK="$c"; break; }
  done
fi

if [ -n "$JDK" ]; then
  JAVAC="$JDK/bin/javac"
  export JAVA_HOME="$JDK"
  # d8 / apksigner 是 java 包装脚本，靠 JAVA_HOME 决定用哪个 JVM
  export PATH="$JDK/bin:$PATH"
  JDK_INFO="17（$JDK）"
else
  JAVAC="${JAVAC:-javac}"
  JDK_INFO="未找到 JDK 17，回退系统 javac"
  echo "!  $JDK_INFO：d8 可能因 JDK 版本崩溃，建议设置 INK_JDK17"
fi

# ---------------------------------------------------------------- python（替代 zip）
PY=""
for p in python python3 py; do
  command -v "$p" >/dev/null 2>&1 && { PY="$p"; break; }
done
[ -n "$PY" ] || die "需要 python 把 classes.dex 写入 APK（本机没有 zip 命令）"

echo "SDK      $SDK"
echo "build    $(basename "$BT")"
echo "JDK      $JDK_INFO"
echo

rm -rf build
mkdir -p build/classes build/dex build/gen

# ---------------------------------------------------------------- 单元测试
if [ "${SKIP_TESTS:-0}" != "1" ]; then
  echo "[1/7] 单元测试"
  # 用 bash 显式调用：检出后 exec 位可能丢失（例如 Windows / zip 中转）
  JAVAC="$JAVAC" bash tests/run.sh
else
  echo "[1/7] 单元测试（已跳过）"
fi

echo "[2/7] aapt2 compile 资源"
"$AAPT2" compile --dir res -o build/res.zip

echo "[3/7] aapt2 link 生成 APK 骨架"
"$AAPT2" link -o build/base.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 \
  --java build/gen build/res.zip

echo "[4/7] javac 编译 Java"
# build/gen 里是 aapt2 生成的 R.java（弹窗主题等资源要引用），必须一起编
find src build/gen -name '*.java' > build/sources.txt
"$JAVAC" --release 8 -encoding UTF-8 -classpath "$PLATFORM" \
  -d build/classes @build/sources.txt 2>build/javac.log || {
    echo "  --release 8 失败，回退到 -source/-target 8"
    "$JAVAC" -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
      -d build/classes @build/sources.txt
  }
grep -v '^注:' build/javac.log 2>/dev/null || true

echo "[5/7] d8 生成 dex"
# shellcheck disable=SC2046
"$D8" --lib "$PLATFORM" --min-api 21 --output build/dex \
  $(find build/classes -name '*.class')

echo "[6/7] 写入 classes.dex"
cp build/base.apk build/unsigned.apk
"$PY" - build/unsigned.apk build/dex/classes.dex <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

echo "[7/7] 对齐并签名"
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=EInk Toolbox, OU=Dev, O=Dev, L=CN, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out eink-toolbox.apk build/aligned.apk
"$APKSIGNER" verify eink-toolbox.apk > /dev/null

rm -f eink-toolbox.apk.idsig
SIZE=$(wc -c < eink-toolbox.apk | tr -d ' ')
echo
echo "完成：eink-toolbox.apk (${SIZE} bytes)"
