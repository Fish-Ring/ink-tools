#!/usr/bin/env bash
# 抓 7 个页面截图，用于重构前后比对。
# 用法：tools/shots.sh <输出目录> [标签]
# 前提：设备已连好 adb；字号档位由人工先设好（导航栏坐标与字号无关，页面内不点其它地方）。
set -uo pipefail
OUT="${1:?用法: tools/shots.sh <输出目录> [标签]}"
TAG="${2:-run}"

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in "$ANDROID_HOME/platform-tools/adb" "D:/Code/Environment/Android/Sdk/platform-tools/adb.exe" adb; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -z "$ADB" ] && { echo "找不到 adb，用 ADB=... 指定"; exit 1; }

export MSYS_NO_PATHCONV=1
mkdir -p "$OUT"

# 屏幕尺寸 → 导航栏点击坐标（与字号无关：导航栏永远贴底）
read -r W H <<<"$("$ADB" shell wm size | tr -d '\r' | sed 's/.*: //' | tr 'x' ' ')"
EY=$((H - H / 24))          # 导航栏竖直中心附近
echo "设备 ${W}x${H}，导航栏点击 y=${EY}"

"$ADB" shell am force-stop ink.tools >/dev/null 2>&1
"$ADB" shell am start -n ink.tools/.MainActivity >/dev/null 2>&1
sleep 3

i=1
for name in dash clock pomo todo habit days set; do
  X=$(( W * (2 * i - 1) / 14 ))     # 第 i 格中心 = W*(i-0.5)/7
  "$ADB" shell input tap "$X" "$EY" >/dev/null 2>&1
  sleep 2
  "$ADB" shell screencap -p /sdcard/_shot.png >/dev/null 2>&1
  "$ADB" pull /sdcard/_shot.png "$OUT/${TAG}_$(printf %02d "$i")_${name}.png" >/dev/null 2>&1
  echo "  · ${name}"
  i=$((i + 1))
done
echo "已保存到 $OUT"
