#!/usr/bin/env bash
# 跑所有不依赖 Android 的单测。
# 只编译「纯逻辑」源码（不允许 import android.*）+ tests/*.java，在桌面 JVM 上直接执行，
# 不需要设备、不需要 Robolectric、不需要任何依赖。
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAC="${JAVAC:-javac}"
JAVA="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi

OUT=build/test-classes
rm -rf "$OUT"
mkdir -p "$OUT"

PURE="src/ink/tools/ClockText.java src/ink/tools/BattMath.java src/ink/tools/DayMath.java src/ink/tools/ChromeMath.java src/ink/tools/CalHead.java src/ink/tools/ClockBox.java src/ink/tools/ClockStrip.java src/ink/tools/HabitScope.java src/ink/tools/HabitTime.java src/ink/tools/BattText.java src/ink/tools/HabitArchive.java src/ink/tools/HabitIds.java src/ink/tools/HabitRec.java src/ink/tools/RecGroup.java src/ink/tools/RecLayout.java"
"$JAVAC" --release 8 -encoding UTF-8 -d "$OUT" $PURE tests/*.java

fail=0
for f in tests/*Test.java; do
  name=$(basename "$f" .java)
  echo "  · $name"
  # 控制台默认 GBK，显式让 JVM 用 UTF-8 输出，中文断言才看得懂
  if ! "$JAVA" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
      -cp "$OUT" "ink.tools.$name"; then fail=1; fi
done

if [ "$fail" = 0 ]; then
  echo "  单测全部通过"
else
  echo "  有单测失败"
  exit 1
fi
