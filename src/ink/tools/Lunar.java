package ink.tools;

import android.os.Build;

/**
 * 农历：直接复用系统 ICU 的中国农历（Android 框架自带，零依赖、零联网）。
 * API < 24 或设备不支持时返回 null，界面隐藏该行。
 */
public final class Lunar {
    private static final String[] MONTH = {"", "正", "二", "三", "四", "五", "六",
            "七", "八", "九", "十", "冬", "腊"};
    private static final String[] NUM = {"", "一", "二", "三", "四", "五", "六",
            "七", "八", "九", "十"};

    private Lunar() {}

    public static String text(long millis) {
        if (Build.VERSION.SDK_INT < 24) return null;
        try {
            android.icu.util.ChineseCalendar cc = new android.icu.util.ChineseCalendar();
            cc.setTimeInMillis(millis);
            int m = cc.get(android.icu.util.Calendar.MONTH) + 1;
            int d = cc.get(android.icu.util.Calendar.DAY_OF_MONTH);
            if (m < 1 || m > 12 || d < 1 || d > 30) return null;
            return "农历 " + MONTH[m] + "月" + dayName(d);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String dayName(int d) {
        if (d == 10) return "初十";
        if (d == 20) return "二十";
        if (d == 30) return "三十";
        String pre = d < 10 ? "初" : (d < 20 ? "十" : (d < 30 ? "廿" : "三"));
        int n = d < 10 ? d : (d < 20 ? d - 10 : (d < 30 ? d - 20 : d - 30));
        return pre + (n == 0 ? "" : NUM[n]);
    }
}
