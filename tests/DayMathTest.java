/**
 * 待办范围（昨天 / 本周）的日期边界单测：周一与周日两端、跨月、跨年、闰日。
 * 零依赖：
 *   javac -d /tmp/dt src/ink/tools/DayMath.java tests/DayMathTest.java
 *   java  -cp /tmp/dt ink.tools.DayMathTest
 */
package ink.tools;

import java.util.Calendar;

public class DayMathTest {
    static int pass = 0, fail = 0;

    static void eq(String what, long got, long exp) {
        if (got == exp) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void ok(String what, boolean v) {
        if (v) { pass++; System.out.println("OK   " + what); }
        else { fail++; System.out.println("FAIL " + what); }
    }

    /** 造一个指定日期的日历（月份按人读的 1-12）。 */
    static Calendar at(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, d, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    public static void main(String[] args) {
        // ---- 基本换算 ----
        Calendar base = at(2026, 11, 8);                 // 2026-11-08 是周日
        eq("ymd", DayMath.ymd(base), 20261108L);
        eq("昨天", DayMath.offset(base, -1), 20261107L);
        eq("明天", DayMath.offset(base, 1), 20261109L);
        eq("周日 weekIndex", DayMath.weekIndex(base), 6);
        eq("周一 weekIndex", DayMath.weekIndex(at(2026, 11, 2)), 0);

        // ---- 本周：周一到周日 ----
        ok("周一在内", DayMath.inWeek(base, 20261102L));
        ok("当天在内", DayMath.inWeek(base, 20261108L));
        ok("上周日不在", !DayMath.inWeek(base, 20261101L));
        ok("下周一不在", !DayMath.inWeek(base, 20261109L));
        ok("非法日期不在", !DayMath.inWeek(base, 0L));

        // ---- 跨月：11-01 是周日，那一周从 10-26 起 ----
        Calendar nov1 = at(2026, 11, 1);
        ok("跨月：10-26 属于本周", DayMath.inWeek(nov1, 20261026L));
        ok("跨月：10-25 不在", !DayMath.inWeek(nov1, 20261025L));
        ok("跨月：11-01 在内", DayMath.inWeek(nov1, 20261101L));

        // ---- 跨年：2027-01-01 是周五，那一周从 2026-12-28 起 ----
        Calendar nyd = at(2027, 1, 1);
        ok("跨年：12-28 属于本周", DayMath.inWeek(nyd, 20261228L));
        ok("跨年：12-27 不在", !DayMath.inWeek(nyd, 20261227L));
        ok("跨年：01-01 在内", DayMath.inWeek(nyd, 20270101L));
        ok("跨年：01-04 不在", !DayMath.inWeek(nyd, 20270104L));

        // ---- 闰日：2028-03-01 的昨天是 02-29 ----
        eq("闰日昨天", DayMath.offset(at(2028, 3, 1), -1), 20280229L);
        eq("平年昨天", DayMath.offset(at(2027, 3, 1), -1), 20270228L);

        // ---- 月底 ----
        eq("月初的昨天跨月", DayMath.offset(at(2026, 12, 1), -1), 20261130L);

        System.out.println("\npass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
