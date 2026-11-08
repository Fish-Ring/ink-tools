package ink.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 打卡记录的分组计算：按月区分，月内平铺（不再细分到习惯）。
 *
 * 页面只负责画，顺序与筛选全在这里，纯逻辑、可单测。
 *
 * 为什么是「月 → 平铺」：
 * - 「全部」跨了很多个月，只按习惯分组会让人找不到某个月，所以每个月先起一个头；
 * - 月内如果再做「习惯」二级分组，两三个习惯就把一页切碎，同一天的两次打卡隔着几个
 *   标题，反而不如一行一条、行首写清是哪个习惯来得直白（见 {@link #flatten}）。
 */
public final class HabitRec {

    /** 一条打卡记录。 */
    public static final class Row {
        public int ym;                 // yyyyMM
        public int day;                // 1..31
        public int mins;               // HabitTime 编码值（0 未打卡 / -1 时间未知）
        public String habitId = "";
        public String habitName = "";
    }

    /** 一个「月 + 习惯」的小组。 */
    public static final class Grp {
        public int ym;
        public String habitId = "";
        public String habitName = "";
        public final ArrayList<Row> rows = new ArrayList<>();
    }

    /** 一个月的分组集合（内部按习惯分段）。 */
    public static final class MonthGrp {
        public int ym;
        public final ArrayList<Grp> groups = new ArrayList<>();
        public int total() {
            int n = 0;
            for (Grp g : groups) n += g.rows.size();
            return n;
        }
    }

    /** 摊平后的一个月：只有月份 + 明细，不再按习惯分段。 */
    public static final class Flat {
        public int ym;
        public final ArrayList<Row> rows = new ArrayList<>();
    }

    private HabitRec() {
    }

    /**
     * 把「月 → 习惯 → 明细」摊平成「月 → 明细」。
     *
     * 记录页一开始是「月 → 习惯」两级分组，但习惯只有两三个时，
     * 每个习惯一个小标题反而把一页切得七零八落，用户还得在两段里找同一天的记录。
     * 现在改为平铺，行首自带习惯名（由页面画），要只看某一个习惯就选它。
     *
     * 排序：先按天倒序；同一天里多个习惯按习惯原有顺序（稳定排序，不会每次刷新换位置）。
     *
     * @param habitId 只留这个习惯；null 或空表示全部
     */
    public static ArrayList<Flat> flatten(ArrayList<MonthGrp> months, String habitId) {
        ArrayList<Flat> out = new ArrayList<>();
        if (months == null) return out;
        boolean only = habitId != null && !habitId.isEmpty();
        for (MonthGrp m : months) {
            Flat f = new Flat();
            f.ym = m.ym;
            for (Grp g : m.groups) {
                if (only && !habitId.equals(g.habitId)) continue;
                f.rows.addAll(g.rows);
            }
            if (f.rows.isEmpty()) continue;
            java.util.Collections.sort(f.rows, new java.util.Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    return a.day < b.day ? 1 : (a.day > b.day ? -1 : 0);
                }
            });
            out.add(f);
        }
        return out;
    }

    /**
     * @param data   所有有数据的月份（内容不限顺序，内部会自己按月倒序排）
     * @param scope  0 本周 / 1 本月 / 2 上月 / 3 全部
     * @param today  今天
     * @param names  习惯 id → 当前名字（改名后历史跟着新名字走；没有的用归档里的旧名）
     */
    public static ArrayList<MonthGrp> build(ArrayList<HabitArchive.Month> data, int scope,
                                            Calendar today, Map<String, String> names) {
        ArrayList<MonthGrp> out = new ArrayList<>();
        if (data == null || data.isEmpty()) return out;

        int curYm = ym(today);
        int prevYm = prevYm(curYm);
        long wkStart = DayMath.weekStart(today);
        long wkEnd = DayMath.weekEnd(today);
        int todayDom = today.get(Calendar.DAY_OF_MONTH);

        // 只保留与范围有关的月份
        ArrayList<HabitArchive.Month> use = new ArrayList<>();
        for (HabitArchive.Month m : data) {
            boolean keep;
            if (scope == HabitScope.ALL) {
                keep = true;
            } else if (scope == HabitScope.MONTH) {
                keep = m.ym == curYm;
            } else if (scope == HabitScope.PREV) {
                keep = m.ym == prevYm;
            } else {                                   // 本周：跨月时两周都算
                keep = m.ym == ymOf(wkStart) || m.ym == ymOf(wkEnd);
            }
            if (keep) use.add(m);
        }
        // 月份倒序：新的在上
        java.util.Collections.sort(use, new java.util.Comparator<HabitArchive.Month>() {
            @Override
            public int compare(HabitArchive.Month a, HabitArchive.Month b) {
                return a.ym < b.ym ? 1 : (a.ym > b.ym ? -1 : 0);
            }
        });

        for (HabitArchive.Month m : use) {
            int from = 1, to = daysInMonth(m.ym);
            if (scope == HabitScope.WEEK) {
                // 本周：只取周一至今天，且不越过今天
                if (m.ym == ymOf(wkStart)) from = Math.max(from, dayOf(wkStart));
                if (m.ym == ymOf(wkEnd)) to = Math.min(to, dayOf(wkEnd));
                if (m.ym == curYm) to = Math.min(to, todayDom);
                if (from > to) continue;
            } else if (scope == HabitScope.MONTH) {
                to = Math.min(to, todayDom);
                if (from > to) continue;
            }

            MonthGrp mg = new MonthGrp();
            mg.ym = m.ym;
            for (HabitArchive.Entry e : m.entries) {
                int[] mins = HabitTime.decode(e.mins);
                ArrayList<Row> rows = new ArrayList<>();
                for (int d = from; d <= to; d++) {
                    if (!e.marked(d)) continue;
                    Row r = new Row();
                    r.ym = m.ym;
                    r.day = d;
                    r.mins = d < mins.length ? mins[d] : HabitTime.NONE;
                    r.habitId = e.id;
                    r.habitName = nameOf(e, names);
                    rows.add(r);
                }
                if (rows.isEmpty()) continue;
                // 习惯内部按日期倒序：最近的在前
                java.util.Collections.sort(rows, new java.util.Comparator<Row>() {
                    @Override
                    public int compare(Row a, Row b) {
                        return a.day < b.day ? 1 : (a.day > b.day ? -1 : 0);
                    }
                });
                Grp g = new Grp();
                g.ym = m.ym;
                g.habitId = e.id;
                g.habitName = rows.get(0).habitName;
                g.rows.addAll(rows);
                mg.groups.add(g);
            }
            if (!mg.groups.isEmpty()) out.add(mg);
        }
        return out;
    }

    /** 习惯显示名：优先用当前的（改名后历史跟着走），否则用归档里的旧名。 */
    public static String nameOf(HabitArchive.Entry e, Map<String, String> names) {
        if (names != null && e.id != null && !e.id.isEmpty()) {
            String n = names.get(e.id);
            if (n != null && !n.isEmpty()) return n;
        }
        return e.name;
    }

    // ---------------------------------------------------------------- 日历小工具

    public static int ym(Calendar c) {
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
    }

    public static int ymOf(long ymd) {
        return (int) (ymd / 10000L) * 100 + (int) (ymd / 100L % 100L);
    }

    public static int dayOf(long ymd) {
        return (int) (ymd % 100L);
    }

    /** 上一个月的 yyyyMM。 */
    public static int prevYm(int ym) {
        int y = ym / 100, m = ym % 100;
        m--;
        if (m < 1) { m = 12; y--; }
        return y * 100 + m;
    }

    /** 该月天数（闰年也对）。 */
    public static int daysInMonth(int ym) {
        Calendar c = Calendar.getInstance();
        c.set(ym / 100, ym % 100 - 1, 1);
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
