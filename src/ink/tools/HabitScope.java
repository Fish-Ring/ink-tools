package ink.tools;

/**
 * 打卡记录页的范围。
 *
 * 前提（必须诚实）：以前 Habit.days[]/mins[] 是当月 32 位数组，跨月清空，
 * 上个月的数据在磁盘上根本不存在，所以只能给「本周 / 本月」两个真实有数据的档。
 * 现在跨月时会把当月位图归档（见 HabitArchive / Store.rolloverIfNeeded），
 * 历史真的存下来了，于是「上月 / 全部」才成为可能。
 *
 * 但归档只能从**装上这一版之后**开始积累：更早的月份当时就没存过，回溯不了。
 * 纯逻辑，不 import android.*，桌面 JVM 可测。
 */
public final class HabitScope {

    /** 本周（周一至周日）。 */
    public static final int WEEK = 0;
    /** 本月。 */
    public static final int MONTH = 1;
    /** 上月。 */
    public static final int PREV = 2;
    /** 全部（所有归档月 + 当月）。 */
    public static final int ALL = 3;
    /** 习惯筛选：全部习惯。 */
    public static final int ALL_HABITS = -1;

    private HabitScope() {
    }

    /** 范围名，用于表头与导出。 */
    public static String name(int scope) {
        if (scope == WEEK) return "本周";
        if (scope == MONTH) return "本月";
        if (scope == PREV) return "上月";
        return "全部";
    }

    /** 习惯数变化后把筛选游标夹回合法区间；返回夹紧后的值。 */
    public static int clamp(int filter, int count) {
        if (filter == ALL_HABITS) return ALL_HABITS;
        if (count <= 0) return ALL_HABITS;
        return filter >= 0 && filter < count ? filter : ALL_HABITS;
    }
}
