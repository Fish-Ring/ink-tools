#!/usr/bin/env bash
# 比对两次截图：静态页逐字节比对（墨水屏无动画、抗锯齿关闭，同一状态应完全一致）。
# 台历 / 时钟 含实时时间，番茄含倒计时，不比 md5，只提示「需人眼确认」。
# 用法：tools/shotcmp.sh <基线目录> <新目录>
set -uo pipefail
A="${1:?用法: tools/shotcmp.sh <基线> <新>}"
B="${2:?用法: tools/shotcmp.sh <基线> <新>}"

STATIC="todo habit days set"
same=0; diff=0; manual=0
printf '%-10s %s\n' "页面" "结果"
for f in "$A"/*.png; do
  n=$(basename "$f")
  page=$(echo "$n" | sed -E 's/^[^_]+_[0-9]+_//; s/\.png$//')
  if [ ! -f "$B/$n" ]; then printf '%-10s %s\n' "$page" "缺图"; diff=$((diff+1)); continue; fi
  if ! echo " $STATIC " | grep -q " $page "; then
    printf '%-10s %s\n' "$page" "含实时内容，跳过逐字节比对"
    manual=$((manual+1)); continue
  fi
  if cmp -s "$f" "$B/$n"; then printf '%-10s %s\n' "$page" "一致"; same=$((same+1));
  else printf '%-10s %s\n' "$page" "**有差异**"; diff=$((diff+1)); fi
done
echo "—— 一致 $same · 有差异 $diff · 人工确认 $manual"
[ "$diff" = 0 ] || exit 1
