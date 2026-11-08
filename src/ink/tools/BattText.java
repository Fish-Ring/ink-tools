package ink.tools;

import java.util.Locale;

/**
 * 电量统计的文案与折算：纯函数，不碰 Android，桌面 JVM 可直接单测。
 *
 * 单独成类的原因：这些数字以前散在 Screen 里用字符串拼，单位、精度、除以零各写一遍，
 * 电量页就出现「有的字很大、有的很小、有的 0.0」。规则集中在这里，页面上只负责排版。
 */
public final class BattText {

    private BattText() {
    }

    /** 「不足 1 分钟」/「38 分钟」/「3 小时 20 分」/「3 小时」。 */
    public static String dur(long ms) {
        if (ms < 60000L) return "不足 1 分钟";
        long m = ms / 60000L;
        if (m < 60) return m + " 分钟";
        long h = m / 60, r = m % 60;
        return r == 0 ? h + " 小时" : h + " 小时 " + r + " 分";
    }

    /** 主数字（放大那一行用），不带单位：分钟数 < 60 时给分钟，否则给小时。 */
    public static String hero(long ms) {
        long m = Math.max(0L, ms / 60000L);
        if (m < 60) return String.valueOf(m);
        double h = m / 60.0;
        if (h < 10) return String.format(Locale.US, "%.1f", h);
        return String.valueOf(Math.round(h));
    }

    /** {@link #hero(long)} 的单位。 */
    public static String heroUnit(long ms) {
        return ms / 60000L < 60 ? "分钟" : "小时";
    }

    /**
     * 折算 %/小时。样本不足 5 分钟返回空串——1% 的粒度下那个数字会离谱（60 分钟 1% 与
     * 5 分钟 1% 差 12 倍）；掉电为 0 时给「不足 0.1」，比 0.0 诚实。
     */
    public static String rate(int drop, long ms) {
        double hours = ms / 3600000.0;
        if (hours < 0.08) return "";
        if (drop <= 0) return "不足 0.1";
        return String.format(Locale.US, "%.1f", drop / hours);
    }

    /**
     * 按「已统计天数」折算 period 天的使用时长（小时）；days 无效时返回 0。
     * 例：3 天用了 6 小时 → 30 天约 60 小时。
     */
    public static double estHours(long ms, int days, int period) {
        if (days <= 0) return 0;
        return ms / 3600000.0 * period / days;
    }

    /** 小时数 → 「0.3」/「12.5」/「120」，超过 100 小时不再保留小数。 */
    public static String hours(double h) {
        if (h <= 0) return "0";
        if (h < 100) return String.format(Locale.US, "%.1f", h);
        return String.valueOf(Math.round(h));
    }
}
