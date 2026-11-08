package ink.tools;

/**
 * 打卡时间的编解码。
 *
 * 存储格式：每个日期一个整数，0 = 未打卡，-1 = 打过卡但没有时间（旧数据或补记），
 * 其余 = 「当天第几分钟 + 1」（00:00 记成 1，避免与「未打卡」撞车）。
 *
 * 一天一个 int，JSON 里是一串逗号分隔的 31 个数，比 31 个时间戳省 3/4 体积，
 * 月历格子、最近记录、导出都能直接用。纯逻辑，可单测。
 */
public final class HabitTime {
    /** 未打卡。 */
    public static final int NONE = 0;
    /** 打过卡，但没有时间（旧数据迁移 / 补记）。 */
    public static final int UNKNOWN = -1;
    public static final int DAYS = 31;

    private HabitTime() {
    }

    /** 时:分 → 存储值（0:00 记成 1）。 */
    public static int encode(int hour, int minute) {
        int h = Math.max(0, Math.min(23, hour));
        int m = Math.max(0, Math.min(59, minute));
        return h * 60 + m + 1;
    }

    /** 存储值 → 「07:12」；未知/未打卡返回空串。 */
    public static String hhmm(int v) {
        if (v <= 0) return "";
        int t = v - 1;
        return two(t / 60) + ":" + two(t % 60);
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** 31 个数 → 逗号串（下标 1..31，下标 0 不存）。 */
    public static String encodeArr(int[] mins) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= DAYS; i++) {
            if (i > 1) b.append(',');
            b.append(mins == null || i >= mins.length ? 0 : mins[i]);
        }
        return b.toString();
    }

    /** 逗号串 → 长度 32 的数组，坏数据当 0 处理，绝不抛。 */
    public static int[] decode(String s) {
        int[] r = new int[32];
        if (s == null || s.isEmpty()) return r;
        String[] p = s.split(",");
        for (int i = 0; i < DAYS && i < p.length; i++) {
            try {
                r[i + 1] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException ignored) {
                r[i + 1] = 0;
            }
        }
        return r;
    }

    /** 旧数据迁移：只有 '0'/'1' 位串，有打卡但无时间，标成 UNKNOWN。 */
    public static int[] migrate(String bits) {
        int[] r = new int[32];
        if (bits == null) return r;
        for (int i = 0; i < DAYS && i < bits.length(); i++) {
            r[i + 1] = bits.charAt(i) == '1' ? UNKNOWN : NONE;
        }
        return r;
    }
}
