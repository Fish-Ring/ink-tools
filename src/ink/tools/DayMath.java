package ink.tools;

import java.util.Calendar;

/**
 * 纯日历换算：待办范围（今天 / 昨天 / 本周）用到的日期比较。
 *
 * 单独成类是为了能脱离 Android 做单测——「本周」要处理周一/周日、跨月、跨年、闰日，
 * 这些边界靠手点界面是测不出来的。不 import 任何 android.*，桌面 JVM 直接跑。
 */
public final class DayMath {
    private DayMath() {
    }

    /** yyyyMMdd，全应用统一的日期键。 */
    public static long ymd(Calendar c) {
        return c.get(Calendar.YEAR) * 10000L
                + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 相对 base 偏移 offset 天的 yyyyMMdd（-1 = 昨天）。 */
    public static long offset(Calendar base, int offset) {
        Calendar c = (Calendar) base.clone();
        c.add(Calendar.DAY_OF_MONTH, offset);
        return ymd(c);
    }

    /** 周一 = 0，周日 = 6。Calendar 里周日是 1，先平移再取模。 */
    public static int weekIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /** target(yyyyMMdd) 是否落在 base 所在那一周（周一至周日）。 */
    public static boolean inWeek(Calendar base, long target) {
        if (target <= 0) return false;
        Calendar c = (Calendar) base.clone();
        c.add(Calendar.DAY_OF_MONTH, -weekIndex(c));
        long start = ymd(c);
        c.add(Calendar.DAY_OF_MONTH, 6);
        return target >= start && target <= ymd(c);
    }

    /** 本周一（周一至周日那一周）的 yyyyMMdd。 */
    public static long weekStart(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.add(Calendar.DAY_OF_MONTH, -weekIndex(c));
        return ymd(x);
    }

    /** 本周日（含）的 yyyyMMdd。 */
    public static long weekEnd(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.add(Calendar.DAY_OF_MONTH, 6 - weekIndex(c));
        return ymd(x);
    }

    /** 时间戳 → yyyyMM（清单记录按月分组用）。 */
    public static int ym(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
    }

    /** yyyyMM + 日 → yyyyMMdd。 */
    public static long ymdOf(int ym, int day) {
        return (long) (ym / 100) * 10000L + (long) (ym % 100) * 100L + day;
    }
}
