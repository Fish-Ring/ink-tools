package ink.tools;

import java.util.Calendar;

/** 时间字符串与字符级差异计算。 */
public final class ClockText {
    private ClockText() {}

    public static String time(long millis, boolean h24, boolean seconds) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (!h24) { h = h % 12; if (h == 0) h = 12; }
        String s = two(h) + ":" + two(c.get(Calendar.MINUTE));
        if (seconds) s += ":" + two(c.get(Calendar.SECOND));
        return s;
    }

    public static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /**
     * 返回首个 / 最后一个变化字符的索引；完全相同返回 null；长度不同返回整段。
     * 13:33 -> 13:34 只返回 {4,4}，因此可以只 invalidate 一个字符的矩形。
     */
    public static int[] diff(String a, String b) {
        if (a == null || a.length() != b.length()) return new int[]{0, b.length() - 1};
        int s = -1, e = -1;
        for (int i = 0; i < b.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                if (s < 0) s = i;
                e = i;
            }
        }
        return s < 0 ? null : new int[]{s, e};
    }
}
