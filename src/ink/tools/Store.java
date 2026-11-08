package ink.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * 极轻本地存储：设置放 SharedPreferences，业务数据放一个 JSON 字符串。
 * 不用 SQLite / Room / 文件数据库，零依赖。
 */
public final class Store {

    public static final class Todo {
        public String text;
        public boolean done;
        public long day;
        public long addAt;                     // 加入时间戳
        public long doneAt;                    // 完成时间戳；0 表示未完成
        public Todo(String t, boolean d, long day) {
            text = t; done = d; this.day = day;
            addAt = System.currentTimeMillis();
            doneAt = d ? addAt : 0;
        }
        /** 状态与完成时间的唯一写入口，避免两处不同步。 */
        public void setDone(boolean v) {
            done = v;
            doneAt = v ? System.currentTimeMillis() : 0;
        }
    }

    public static final class Habit {
        public String id = "";                   // 稳定标识：改名后历史仍跟得上
        public String name;
        public int streak;
        public final boolean[] days = new boolean[32];
        /** 每个日期打卡的时刻：0 未打卡 / -1 打过卡但无时间 / 其余见 HabitTime。 */
        public int[] mins = new int[32];
        public Habit(String n) { name = n; }
    }    public static final class Day {
        public String name;
        public long millis;
        public boolean pinned;                 // 置顶到台历右上角，全列表唯一
        public boolean onClock;                // 在时钟页数字下方显示，全列表唯一
        public Day(String n, long m) { name = n; millis = m; }
    }

    public static final class Pomo {
        public int focus = 25, shortBreak = 5, longBreak = 15, rounds = 4;
    }

    /** 番茄历史：按天记录完成轮数与专注分钟。 */
    public static final class PomoDay {
        public long day;                       // yyyyMMdd
        public int rounds, minutes;
        PomoDay(long d, int r, int m) { day = d; rounds = r; minutes = m; }
    }

    private final SharedPreferences p;
    public boolean showSeconds, h24, lunar, keepOn, oled;
    public float fontScale;
    public final Pomo pomo = new Pomo();
    public final ArrayList<Todo> todos = new ArrayList<>();
    public final ArrayList<Habit> habits = new ArrayList<>();
    public final ArrayList<Day> days = new ArrayList<>();
    public int pomoDoneToday;
    public final ArrayList<PomoDay> pomoHist = new ArrayList<>();   // 最多 400 天（约 13 个月）
    private int pomoAllRounds, pomoAllMinutes;   // 终身累计：只增不减，明细被淘汰也不丢
    private long pomoDoneDay;
    private int habitMonth;                   // yyyyMM，用于跨月清空打卡格

    /**
     * 打卡历史归档：跨月时把当月位图另存一份，历史按月回看靠它。
     *
     * 以前 Habit.days[]/mins[] 跨月直接清空，上个月的记录在磁盘上就不存在了，
     * 「按月查看历史」连数据都没有。现在每月结束归档一次，旧数据只增不减。
     */
    private final ArrayList<HabitArchive.Month> habitArch = new ArrayList<>();
    private boolean habitArchDirty;            // 归档变了才落盘，别每次打卡都重写一遍大字符串
    private int nextHabitId = 1;               // 习惯 id 计数器（改名不影响历史归属）
    private boolean idDirty;                   // 老数据补 id 后需要落盘

    /** 习惯 id：稳定标识。改名后历史仍跟得上，删除后历史仍在（用归档里的旧名）。 */
    public String newHabitId() {
        return "h" + (nextHabitId++);
    }

    // ---------------------------------------------------------------- 电量统计
    // 累加算法在 BattMath（纯逻辑、桌面 JVM 可单测）；这里只负责跨天归档、
    // 充电状态翻转与落盘。
    //
    // 关键点：充电检测不依赖「App 正在运行」。电量上升就判定为中间充过电，
    // 所以「插着电充到满、期间没打开 App」也能正确结束上一轮。

    public BattMath.Acc battToday = new BattMath.Acc();      // 今天
    public BattMath.Acc battCharge = new BattMath.Acc();     // 本次充电后（拔电起算）
    /**
     * 全时段累加：从第一次开始统计起一直加，跨天、跨充电都不清零。
     * 只存「合计 + 起算日」，不存明细——电量只有 1% 粒度，逐年明细没有信息量。
     */
    public BattMath.Acc battAll = new BattMath.Acc();
    public long battFirst;                                   // 全时段起算日 yyyyMMdd
    public final ArrayList<BattMath.Acc> battArch = new ArrayList<>();   // 归档日桶，最多 6 条
    public boolean battCharging = false;
    private long battDay;                          // 今日累加器属于哪一天
    private long battSavedAt;
    private boolean battSeeded;

    public Store(Context c) {
        p = c.getSharedPreferences("eink", Context.MODE_PRIVATE);
        showSeconds = p.getBoolean("sec", false);
        h24 = p.getBoolean("h24", true);
        lunar = p.getBoolean("lunar", true);
        keepOn = p.getBoolean("keep", true);
        oled = p.getBoolean("oled", false);
        // 字号档位整体上移（1.3 起步）：老版本存下的 0.9~1.15 直接提到标准档，
        // 否则会出现「设置页写着 1/5，实际仍按 0.9 画」的错位
        fontScale = clampFont(p.getFloat("fs", 1.3f));
        pomo.focus = p.getInt("pf", 25);
        pomo.shortBreak = p.getInt("ps", 5);
        pomo.longBreak = p.getInt("pl", 15);
        pomo.rounds = p.getInt("pr", 4);
        pomoDoneToday = p.getInt("pd", 0);
        pomoDoneDay = p.getLong("pdd", 0);
        habitMonth = p.getInt("hm", 0);
        nextHabitId = p.getInt("hi", 1);
        loadHabitArch(p.getString("ha", ""));
        loadPomoHist(p.getString("ph", ""));
        loadBatt(p.getString("bt1", ""), p.getString("bt2", ""), p.getString("bt7", ""),
                p.getLong("btd", 0), p.getBoolean("btc", false), p.getBoolean("bts", false));
        // 旧版本的采样点数组已不再使用，顺手清掉，别白占空间
        if (p.contains("btl") || p.contains("btp") || p.contains("bta")) {
            p.edit().remove("btl").remove("btp").remove("bta").apply();
        }
        pomoAllRounds = p.getInt("par", 0);
        pomoAllMinutes = p.getInt("pam", 0);
        if (pomoAllRounds == 0 && !pomoHist.isEmpty()) {
            // 老版本只存了最近 30 天、没有终身累计字段：用现有明细当基数，之后只增
            for (PomoDay d : pomoHist) { pomoAllRounds += d.rounds; pomoAllMinutes += d.minutes; }
        }
        String j = p.getString("data", "");
        if (j.isEmpty()) samples();
        else load(j);                          // 加载失败也绝不覆盖用户数据
        syncNextHabitId();
        if (rolloverIfNeeded() || idDirty) save();
    }

    /** yyyyMMdd，用于「今日」判定与跨天重置统计。 */
    public static long today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000L
                + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 相对今天偏移 offset 天的 yyyyMMdd（-1 = 昨天）。 */
    public static long dayYmd(int offset) {
        return DayMath.offset(Calendar.getInstance(), offset);
    }

    /** ymd 是否落在本周（周一至周日）。待办「本周」分类用。 */
    public static boolean inThisWeek(long ymd) {
        return DayMath.inWeek(Calendar.getInstance(), ymd);
    }

    public static int dayOfMonth() {
        return Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
    }

    public static int daysInMonth() {
        Calendar c = Calendar.getInstance();
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 目标日期距今天的天数，正数为还有，负数为已过。 */
    public static long daysFrom(long millis) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(millis);
        a.set(Calendar.HOUR_OF_DAY, 0);
        a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0);
        a.set(Calendar.MILLISECOND, 0);
        b.set(Calendar.HOUR_OF_DAY, 0);
        b.set(Calendar.MINUTE, 0);
        b.set(Calendar.SECOND, 0);
        b.set(Calendar.MILLISECOND, 0);
        return Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }

    private void samples() {
        long t = today();
        todos.add(new Todo("买贴膜", false, t));
        todos.add(new Todo("整理读书笔记", false, t));
        todos.add(new Todo("晨间阅读 20 分钟", true, t));
        todos.add(new Todo("回复邮件", false, t));
        todos.add(new Todo("备份笔记", true, t));

        Habit h1 = new Habit("阅读");
        Habit h2 = new Habit("运动");
        Habit h3 = new Habit("早睡");
        int dom = dayOfMonth();
        // days[] 与连续天数自洽：save() 会按 days[] 重算 streak
        for (int i = 1; i <= dom; i++) {
            h1.days[i] = (dom - i) < 6;
            h2.days[i] = (dom - i) < 2;
            h3.days[i] = (dom - i) < 4;
        }
        habits.add(h1); habits.add(h2); habits.add(h3);

        Calendar d = Calendar.getInstance();
        d.add(Calendar.DAY_OF_MONTH, 43);
        Day top = new Day("目标日期", d.getTimeInMillis());
        top.pinned = true;
        days.add(top);
        d = Calendar.getInstance(); d.add(Calendar.DAY_OF_MONTH, 12);
        days.add(new Day("体检预约", d.getTimeInMillis()));
        d = Calendar.getInstance(); d.add(Calendar.DAY_OF_MONTH, -208);
        days.add(new Day("纪念日", d.getTimeInMillis()));

        // 番茄历史示例（近 6 天，含今天），与「清空数据」一并可清除
        int[] demo = {2, 4, 3, 5, 1, 4};
        for (int i = 5; i >= 0; i--) {
            Calendar hc = Calendar.getInstance();
            hc.add(Calendar.DAY_OF_MONTH, -i);
            long key = hc.get(Calendar.YEAR) * 10000L
                    + (hc.get(Calendar.MONTH) + 1) * 100L
                    + hc.get(Calendar.DAY_OF_MONTH);
            int v = demo[5 - i];
            pomoHist.add(new PomoDay(key, v, v * 25));
        }
        pomoDoneToday = demo[5];
        pomoDoneDay = t;
        save();
    }

    private boolean load(String j) {
        if (j == null || j.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(j);
            JSONArray a = o.optJSONArray("todos");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    Todo t = new Todo(x.optString("t", ""), false, x.optLong("dt", today()));
                    t.addAt = x.optLong("at", 0);
                    t.done = x.optBoolean("d", false);
                    t.doneAt = x.optLong("ft", t.done ? t.addAt : 0);
                    if (t.done && t.doneAt <= 0) t.doneAt = t.addAt;
                    todos.add(t);
                }
            }
            JSONArray hs = o.optJSONArray("habits");
            if (hs != null) {
                // 习惯 id：老数据没有，补一个并立刻存回去，以后改名不影响历史归属。
                // 发号前先把「已被占用的号」登记一遍：存档里已有的号 + 归档里的历史号
                // （那些习惯可能已删除，但历史还在，号不能复用），
                // 否则老存档缺 id 的习惯会拿到一个已被占用的号 →
                // 两个习惯同 id → 打卡历史串到错的习惯上。
                // 与 restore() 同一套规则（HabitIds）。
                ArrayList<String> fileIds = new ArrayList<>();
                for (int i = 0; i < hs.length(); i++) {
                    fileIds.add(hs.getJSONObject(i).optString("i", ""));
                }
                int reserved = HabitIds.maxNumOfMonths(habitArch);
                ArrayList<String> newIds = HabitIds.assign(fileIds, nextHabitId, reserved);
                nextHabitId = Math.max(nextHabitId, HabitIds.maxNum(newIds) + 1);
                for (int i = 0; i < hs.length(); i++) {
                    JSONObject x = hs.getJSONObject(i);
                    Habit h = new Habit(x.optString("n", ""));
                    h.streak = x.optInt("s", 0);
                    h.id = newIds.get(i);
                    if (!h.id.equals(fileIds.get(i))) idDirty = true;
                    String bits = x.optString("b", "");
                    for (int k = 0; k < 31 && k < bits.length(); k++) {
                        h.days[k + 1] = bits.charAt(k) == '1';
                    }
                    // 没有 m 键就是老数据：按位串迁移成「已打卡 / 时间未知」
                    String ms = x.optString("m", "");
                    h.mins = ms.isEmpty() ? HabitTime.migrate(bits) : HabitTime.decode(ms);
                    habits.add(h);
                }
            }
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) {
                boolean seen = false, seenClock = false;
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject x = ds.getJSONObject(i);
                    Day d = new Day(x.optString("n", ""), x.optLong("m", 0));
                    boolean want = x.optBoolean("p", false) && !seen;
                    d.pinned = want;
                    if (want) seen = true;
                    boolean wantClock = x.optBoolean("c", false) && !seenClock;
                    d.onClock = wantClock;
                    if (wantClock) seenClock = true;
                    days.add(d);
                }
            }
            return true;
        } catch (Exception e) {
            return true;                        // 保留已加载部分，绝不清空重写
        }
    }

    /** 唯一的置顶事件；没有则返回 null。 */
    public Day pinnedDay() {
        for (Day d : days) if (d.pinned) return d;
        return null;
    }

    /** 台历倒数行与大卡片共用的「主事件」：优先置顶，否则第一条。 */
    public Day primaryDay() {
        Day p = pinnedDay();
        return p != null ? p : (days.isEmpty() ? null : days.get(0));
    }

    /** 唯一的「时钟页倒数」事件；没有则返回 null。与台历置顶互不干扰。 */
    public Day clockDay() {
        for (Day d : days) if (d.onClock) return d;
        return null;
    }

    /** 置顶的唯一写入口：先清全部，再按对象身份切换（不受增删导致的下标漂移影响）。 */
    public void togglePin(Day d) {
        if (d == null || days.indexOf(d) < 0) return;
        boolean on = !d.pinned;
        for (Day x : days) x.pinned = false;
        d.pinned = on;
    }

    /** 时钟页倒数的唯一写入口：独立于置顶，允许台历显示 A、时钟显示 B。 */
    public void toggleClockDay(Day d) {
        if (d == null || days.indexOf(d) < 0) return;
        boolean on = !d.onClock;
        for (Day x : days) x.onClock = false;
        d.onClock = on;
    }

    // ------------------------------------------------------------ 番茄统计

    private void loadPomoHist(String s) {
        pomoHist.clear();
        if (s == null || s.isEmpty()) return;
        try {
            for (String part : s.split(";")) {
                if (part.isEmpty()) continue;
                String[] f = part.split(":");
                if (f.length != 3) continue;
                pomoHist.add(new PomoDay(Long.parseLong(f[0]),
                        Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            }
        } catch (Exception ignored) {
        }
    }

    private String encodePomoHist() {
        StringBuilder b = new StringBuilder();
        for (PomoDay d : pomoHist) {
            b.append(d.day).append(':').append(d.rounds)
                    .append(':').append(d.minutes).append(';');
        }
        return b.toString();
    }

    /** 一次专注阶段完成：轮数 +1、累计分钟、写入历史。 */
    public void notePomoDone(int minutes) {
        pomoDoneToday++;
        long t = today();
        PomoDay rec = null;
        for (PomoDay d : pomoHist) if (d.day == t) { rec = d; break; }
        if (rec == null) { rec = new PomoDay(t, 0, 0); pomoHist.add(rec); }
        rec.rounds++;
        rec.minutes += minutes;
        pomoAllRounds++;
        pomoAllMinutes += minutes;
        while (pomoHist.size() > 400) pomoHist.remove(0);
        save();
    }

    private static long dayKey(int back) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -back);
        return c.get(Calendar.YEAR) * 10000L
                + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 最近 7 天每日完成轮数，索引 0 = 6 天前，6 = 今天。 */
    public int[] pomoLast7() {
        int[] r = new int[7];
        for (int i = 0; i < 7; i++) {
            long key = dayKey(6 - i);
            for (PomoDay d : pomoHist) if (d.day == key) { r[i] = d.rounds; break; }
        }
        return r;
    }

    public int pomoLast7Rounds() {
        int n = 0;
        for (int v : pomoLast7()) n += v;
        return n;
    }

    public int pomoLast7Minutes() {
        int n = 0;
        for (int i = 0; i < 7; i++) {
            long key = dayKey(i);
            for (PomoDay d : pomoHist) if (d.day == key) { n += d.minutes; break; }
        }
        return n;
    }

    /** 最近 7 天每日专注分钟，索引 0 = 6 天前，6 = 今天。 */
    public int[] pomoLast7MinutesArr() {
        int[] r = new int[7];
        for (int i = 0; i < 7; i++) {
            long key = dayKey(6 - i);
            for (PomoDay d : pomoHist) if (d.day == key) { r[i] = d.minutes; break; }
        }
        return r;
    }

    /** 本月 / 本年求和。kind 0 = 轮数，1 = 分钟。 */
    private int sumPomo(boolean thisMonth, int kind) {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR), m = c.get(Calendar.MONTH) + 1;
        int n = 0;
        for (PomoDay d : pomoHist) {
            if (d.day / 10000L != y) continue;                       // 只看今年
            if (thisMonth && (d.day / 100L) % 100L != m) continue;   // 再看本月
            n += kind == 0 ? d.rounds : d.minutes;
        }
        return n;
    }

    public int pomoMonthRounds() { return sumPomo(true, 0); }
    public int pomoMonthMinutes() { return sumPomo(true, 1); }
    public int pomoYearRounds() { return sumPomo(false, 0); }
    public int pomoYearMinutes() { return sumPomo(false, 1); }

    /** 历史记录按日期倒序（最新在前），供列表与导出使用。 */
    public java.util.ArrayList<PomoDay> pomoHistoryDesc() {
        java.util.ArrayList<PomoDay> a = new java.util.ArrayList<>(pomoHist);
        java.util.Collections.sort(a, new java.util.Comparator<PomoDay>() {
            @Override public int compare(PomoDay x, PomoDay y) {
                return x.day == y.day ? 0 : (x.day > y.day ? -1 : 1);
            }
        });
        return a;
    }

    private static String durText(int min) {
        if (min < 60) return min + " 分";
        int h = min / 60, m = (min % 60) * 10 / 60;
        return m == 0 ? h + " 时" : h + "." + m + " 时";
    }

    /**
     * 导出用的纯文本：一行一天。
     * 只导调用方传入的范围（界面上选的本月 / 上月 / 全部），
     * 这样文件长度跟着「你正在看的那段」走，不会每次都甩出几百行。
     */
    public String pomoExportText(java.util.List<PomoDay> list, String range) {
        StringBuilder b = new StringBuilder();
        Calendar now = Calendar.getInstance();
        int tr = 0, tm = 0;
        for (PomoDay d : list) { tr += d.rounds; tm += d.minutes; }
        b.append("墨水屏工具箱 · 番茄记录\n");
        b.append(String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d 导出 · %s\n",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE), range));
        b.append("本段 ").append(list.size()).append(" 天 · ")
                .append(tr).append(" 轮 · ").append(durText(tm)).append("\n\n");
        if (list.isEmpty()) {
            b.append("（这段时间没有记录）\n");
        } else {
            b.append("日期        轮   分钟\n");
            for (PomoDay d : list) {
                b.append(String.format(java.util.Locale.US, "%04d-%02d-%02d  %3d  %5d\n",
                        d.day / 10000L, (d.day / 100L) % 100L, d.day % 100L,
                        d.rounds, d.minutes));
            }
        }
        b.append("\n终身累计 ").append(pomoAllRounds).append(" 轮 · ")
                .append(durText(pomoAllMinutes)).append("\n");
        return b.toString();
    }

    /** 终身累计：明细只留 13 个月，累计值单独记。 */
    public int pomoAllRounds() { return pomoAllRounds; }
    public int pomoAllMinutes() { return pomoAllMinutes; }

    public int pomoTotalRounds() { return pomoAllRounds; }
    public int pomoTotalMinutes() { return pomoAllMinutes; }

    /** 跨天重置今日番茄轮数；跨月把当月打卡位图归档后清空。返回是否有变化。 */
    public boolean rolloverIfNeeded() {
        boolean changed = false;
        long t = today();
        if (pomoDoneDay != t) {
            pomoDoneToday = 0;
            pomoDoneDay = t;
            changed = true;
        }
        Calendar c = Calendar.getInstance();
        int hm = c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1);
        if (habitMonth != hm) {
            if (habitMonth != 0) {
                // 归档：这个月的位图是 habitMonth 那个月的，不存下来就永远没了
                HabitArchive.merge(habitArch, snapshotMonth(habitMonth));
                HabitArchive.trim(habitArch);
                habitArchDirty = true;
                for (Habit h : habits) {
                    java.util.Arrays.fill(h.days, false);
                    java.util.Arrays.fill(h.mins, HabitTime.NONE);
                }
            }
            habitMonth = hm;
            changed = true;
        }
        // 归档里若还留着「当前月」，把它并回活位图。
        // 放在最后：跨月清空位图之后活位图是空的，正是要把归档里的当月补回来的时候。
        if (reconcileHabitMonth()) changed = true;
        return changed;
    }

    /**
     * 归档与活位图的对账：把归档里「当前月」的数据并回活位图，并把归档里当月那条删掉。
     *
     * 为什么会不一致：系统时间往回拨又拨回来时（11 月 → 10 月 → 11 月），
     * 同一个 11 月被切成两段，跨月时第一段进了归档、活位图被清空；
     * 拨回 11 月后归档里留着当月数据、活位图却是空的——
     * 打卡页说「本月 0 次」、记录页说当月有记录，两页自相矛盾。
     *
     * 归档的语义是「已经结束的月」，当前月永远是活的：
     * 所以归档里当月的数据要并回活位图（并集，不覆盖用户新打的卡），
     * 且归档里当月那条要移除——否则同一个月两处数据不一致的问题会一直在。
     *
     * 合并规则在 HabitArchive.restoreInto（纯逻辑，可单测）。
     */
    private boolean reconcileHabitMonth() {
        if (habitMonth <= 0) return false;
        ArrayList<HabitArchive.Slot> slots = habitSlots();
        // 归档里没有当月 → 什么都不动（别误删别的月份）
        if (!HabitArchive.reconcileMonth(habitArch, habitMonth, slots)) return false;
        slotsToHabits(slots);
        habitArchDirty = true;                 // 归档变了，要写盘
        return true;
    }

    /** 把当前习惯位图打包成某个月的归档记录。 */
    private HabitArchive.Month snapshotMonth(int ym) {
        return HabitArchive.snapshot(ym, habitSlots());
    }

    /**
     * 当前习惯 → 活位图槽位。
     *
     * 只是搬数据，规则（编解码 / 合并）都在 HabitArchive 里，
     * 这样「归档 ↔ 活位图」的语义能脱离 android.* 单测。
     */
    private ArrayList<HabitArchive.Slot> habitSlots() {
        ArrayList<HabitArchive.Slot> slots = new ArrayList<>();
        for (Habit h : habits) {
            HabitArchive.Slot s = new HabitArchive.Slot();
            s.id = h.id == null ? "" : h.id;
            s.name = h.name == null ? "" : h.name;
            System.arraycopy(h.days, 0, s.days, 0, Math.min(h.days.length, s.days.length));
            System.arraycopy(h.mins, 0, s.mins, 0, Math.min(h.mins.length, s.mins.length));
            slots.add(s);
        }
        return slots;
    }

    /** 槽位写回习惯（恢复合并后用）。 */
    private void slotsToHabits(ArrayList<HabitArchive.Slot> slots) {
        for (int i = 0; i < habits.size() && i < slots.size(); i++) {
            Habit h = habits.get(i);
            HabitArchive.Slot s = slots.get(i);
            System.arraycopy(s.days, 0, h.days, 0, Math.min(s.days.length, h.days.length));
            System.arraycopy(s.mins, 0, h.mins, 0, Math.min(s.mins.length, h.mins.length));
        }
    }

    /**
     * 所有有数据的月份（含当前月），按 ym 从大到小。
     * 当前月是活的（还在打卡），历史月来自归档。
     */
    public ArrayList<HabitArchive.Month> habitMonths() {
        HabitArchive.Month cur = snapshotMonth(habitMonth > 0 ? habitMonth : currentYm());
        // 当前月与归档里同月时不是「替换」而是「合并」（按位取并集）：
        // 系统时间往回拨又拨回来时，同一个月会被切成两段（11 月 → 10 月 → 11 月），
        // 归档里存着第一段、活着的位图是第二段，只留一段就是丢数据。
        return HabitArchive.view(habitArch, cur);
    }

    /** 当前月键 yyyyMM。 */
    public static int currentYm() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
    }

    public String exportJson() {
        return p.getString("data", "");
    }

    // ------------------------------------------------------------ 备份 / 导入

    /** 文件名用的时间戳 yyyyMMdd-HHmm。 */
    public static String fileStamp() {
        Calendar c = Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d%02d%02d-%02d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /**
     * 备份：设置 + 待办 + 习惯 + 倒数日 + 番茄配置。
     * withPomoHist = false 时不写每日明细（那部分一天一条，几百天就顶满整个文件），
     * 番茄明细另有 CSV / TXT 可导，这里只保留「换机能用」的部分。
     */
    public String backupJson(boolean withPomoHist) {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "ink-tools");
            o.put("schema", 2);
            Calendar n = Calendar.getInstance();
            o.put("exported", String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d",
                    n.get(Calendar.YEAR), n.get(Calendar.MONTH) + 1, n.get(Calendar.DAY_OF_MONTH),
                    n.get(Calendar.HOUR_OF_DAY), n.get(Calendar.MINUTE)));

            JSONObject se = new JSONObject();
            se.put("showSeconds", showSeconds);
            se.put("h24", h24);
            se.put("lunar", lunar);
            se.put("keepOn", keepOn);
            se.put("oled", oled);
            se.put("fontScale", fontScale);
            o.put("settings", se);

            JSONObject po = new JSONObject();
            po.put("focus", pomo.focus);
            po.put("shortBreak", pomo.shortBreak);
            po.put("longBreak", pomo.longBreak);
            po.put("rounds", pomo.rounds);
            po.put("doneToday", pomoDoneToday);
            po.put("doneDay", pomoDoneDay);
            if (withPomoHist) {
                po.put("allRounds", pomoAllRounds);
                po.put("allMinutes", pomoAllMinutes);
            }
            o.put("pomo", po);

            if (withPomoHist) {
                JSONArray hs = new JSONArray();
                for (PomoDay d : pomoHist) {
                    JSONObject x = new JSONObject();
                    x.put("d", d.day); x.put("r", d.rounds); x.put("m", d.minutes);
                    hs.put(x);
                }
                o.put("hist", hs);
            }

            // 待办 / 习惯 / 倒数日直接复用存盘字段，格式与 save() 完全一致
            JSONObject data = new JSONObject(p.getString("data", "{}"));
            if (data.has("todos")) o.put("todos", data.get("todos"));
            if (data.has("habits")) o.put("habits", data.get("habits"));
            if (data.has("days")) o.put("days", data.get("days"));
            // 打卡历史归档也进备份：换机后不该只剩当月
            String arch = p.getString("ha", "");
            if (!arch.isEmpty()) o.put("habitArch", arch);
            return o.toString(2);
        } catch (Exception e) {
            return "";
        }
    }

    /** 最近一次 restore 失败的原因，供界面提示。 */
    public String restoreError = "";

    /** 备份里的番茄明细天数：-1 = 不是 Ink Tools 备份或已损坏，0 = 这份备份不含明细。 */
    public static int backupHistDays(String s) {
        try {
            String t = s;
            if (t != null && !t.isEmpty() && t.charAt(0) == '\uFEFF') t = t.substring(1);
            JSONObject o = new JSONObject(t);
            if (!"ink-tools".equals(o.optString("app", ""))) return -1;
            JSONArray a = o.optJSONArray("hist");
            return a == null ? 0 : a.length();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 从备份 JSON 整体还原。先全部解析到临时结构，成功后才替换现有数据——
     * 半个文件不该把用户现有记录冲掉。
     * withPomoHist = false 时番茄每日明细与终身累计保持不动（只恢复配置和其他数据）。
     */
    public boolean restore(String s, boolean withPomoHist) {
        restoreError = "";
        if (s == null || s.trim().isEmpty()) { restoreError = "文件是空的"; return false; }
        try {
            String t = s;
            if (t.charAt(0) == '\uFEFF') t = t.substring(1);
            JSONObject o = new JSONObject(t);
            if (!"ink-tools".equals(o.optString("app", ""))) {
                restoreError = "不是 Ink Tools 的配置文件";
                return false;
            }

            ArrayList<Todo> nt = new ArrayList<>();
            JSONArray a = o.optJSONArray("todos");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    Todo d = new Todo(x.optString("t", ""), false, x.optLong("dt", today()));
                    d.addAt = x.optLong("at", 0);
                    d.done = x.optBoolean("d", false);
                    d.doneAt = x.optLong("ft", d.done ? d.addAt : 0);
                    if (d.done && d.doneAt <= 0) d.doneAt = d.addAt;
                    nt.add(d);
                }
            }

            ArrayList<Habit> nh = new ArrayList<>();
            JSONArray hs = o.optJSONArray("habits");
            // 先把备份里已占用的习惯 id 登记一遍：老备份缺 id 的习惯要在这里发新号，
            // 新号必须避开备份里已有的号，否则两个习惯同 id，打卡历史会串到错的习惯上。
            ArrayList<String> fileIds = new ArrayList<>();
            if (hs != null) {
                for (int i = 0; i < hs.length(); i++) {
                    fileIds.add(hs.getJSONObject(i).optString("i", ""));
                }
            }
            // 归档里的 id 也要避开：那些习惯可能已删除，但历史还在（用旧名兜底）
            int reserved = HabitIds.maxNumOfMonths(HabitArchive.decode(o.optString("habitArch", "")));
            ArrayList<String> newIds = HabitIds.assign(fileIds, nextHabitId, reserved);
            nextHabitId = Math.max(nextHabitId, HabitIds.maxNum(newIds) + 1);
            if (hs != null) {
                for (int i = 0; i < hs.length(); i++) {
                    JSONObject x = hs.getJSONObject(i);
                    Habit h = new Habit(x.optString("n", ""));
                    h.streak = x.optInt("s", 0);
                    h.id = newIds.get(i);
                    if (!h.id.equals(fileIds.get(i))) idDirty = true;
                    String bits = x.optString("b", "");
                    for (int k = 0; k < 31 && k < bits.length(); k++) h.days[k + 1] = bits.charAt(k) == '1';
                    String ms = x.optString("m", "");
                    h.mins = ms.isEmpty() ? HabitTime.migrate(bits) : HabitTime.decode(ms);
                    nh.add(h);
                }
            }

            ArrayList<Day> nd = new ArrayList<>();
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) {
                boolean seen = false, seenClock = false;
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject x = ds.getJSONObject(i);
                    Day d = new Day(x.optString("n", ""), x.optLong("m", 0));
                    if (x.optBoolean("p", false) && !seen) { d.pinned = true; seen = true; }
                    if (x.optBoolean("c", false) && !seenClock) {
                        d.onClock = true;
                        seenClock = true;
                    }
                    nd.add(d);
                }
            }

            ArrayList<PomoDay> np = new ArrayList<>();
            JSONArray ph = o.optJSONArray("hist");
            if (withPomoHist && ph != null) {
                for (int i = 0; i < ph.length(); i++) {
                    JSONObject x = ph.getJSONObject(i);
                    np.add(new PomoDay(x.optLong("d", 0), x.optInt("r", 0), x.optInt("m", 0)));
                }
                while (np.size() > 400) np.remove(0);
            }

            JSONObject se = o.optJSONObject("settings");
            JSONObject po = o.optJSONObject("pomo");

            // —— 解析全部成功，从这里开始才动真实数据 ——
            todos.clear(); todos.addAll(nt);
            habits.clear(); habits.addAll(nh);
            days.clear(); days.addAll(nd);
            if (withPomoHist) {
                pomoHist.clear(); pomoHist.addAll(np);
            }

            if (se != null) {
                showSeconds = se.optBoolean("showSeconds", showSeconds);
                h24 = se.optBoolean("h24", h24);
                lunar = se.optBoolean("lunar", lunar);
                keepOn = se.optBoolean("keepOn", keepOn);
                oled = se.optBoolean("oled", oled);
                float f = (float) se.optDouble("fontScale", fontScale);
                fontScale = clampFont(f);
            }
            if (po != null) {
                pomo.focus = clamp(po.optInt("focus", pomo.focus), 5, 90);
                pomo.shortBreak = clamp(po.optInt("shortBreak", pomo.shortBreak), 1, 30);
                pomo.longBreak = clamp(po.optInt("longBreak", pomo.longBreak), 5, 60);
                pomo.rounds = clamp(po.optInt("rounds", pomo.rounds), 2, 8);
                if (withPomoHist) {
                    // 今日进度跟明细是一套的：只导配置时不要把「今天做了几轮」改成备份里的旧值
                    pomoDoneToday = Math.max(0, po.optInt("doneToday", pomoDoneToday));
                    long dd = po.optLong("doneDay", pomoDoneDay);
                    if (dd > 0) pomoDoneDay = dd;
                    pomoAllRounds = Math.max(0, po.optInt("allRounds", pomoAllRounds));
                    pomoAllMinutes = Math.max(0, po.optInt("allMinutes", pomoAllMinutes));
                }
            }
            // 打卡月份归到当前月：导入旧备份后不会因为「跨月」把整月考勤清掉
            Calendar c = Calendar.getInstance();
            habitMonth = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;

            // 历史归档：只认备份里带来的，没有就清空（不能留旧机器的）
            loadHabitArch(o.optString("habitArch", ""));
            habitArchDirty = true;
            syncNextHabitId();

            save();
            return true;
        } catch (Exception e) {
            restoreError = "解析失败，文件可能已损坏";
            return false;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 字号只接受当前档位区间，老数据 / 手改的备份都会落到标准档上。 */
    static float clampFont(float f) {
        return Math.max(Screen.FONT_MIN, Math.min(Screen.FONT_MAX, f));
    }

    /** 番茄历史导出为 CSV：Excel / WPS 双击即表格，UTF-8 带 BOM 中文不乱码。 */
    public String pomoExportCsv(java.util.List<PomoDay> list) {
        StringBuilder b = new StringBuilder();
        b.append('\uFEFF');
        b.append("日期,星期,轮数,专注分钟\r\n");
        Calendar c = Calendar.getInstance();
        String[] wk = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int tr = 0, tm = 0;
        for (PomoDay d : list) {
            int y = (int) (d.day / 10000L), mo = (int) ((d.day / 100L) % 100L), dd = (int) (d.day % 100L);
            c.set(y, mo - 1, dd);
            b.append(String.format(java.util.Locale.US, "%04d-%02d-%02d", y, mo, dd)).append(',')
                    .append(wk[c.get(Calendar.DAY_OF_WEEK) - 1]).append(',')
                    .append(d.rounds).append(',').append(d.minutes).append("\r\n");
            tr += d.rounds;
            tm += d.minutes;
        }
        b.append("合计,,").append(tr).append(',').append(tm).append("\r\n");
        b.append("终身累计,,").append(pomoAllRounds).append(',').append(pomoAllMinutes).append("\r\n");
        return b.toString();
    }

    /**
     * 每分钟跟着 tick 采一次，start / stop 也各调一次，把前后台边界对齐。
     *
     * 三次口径共用同一套未充电累加（今日、本次充电后、全时段），
     * 近 7 天由归档日桶 + 今日算出；总时长（含充电）由 gross() 单独维护。
     * 不记明细、不新增定时器——电量本身只有 1% 粒度，写得再密也没有多余信息。
     */
    public void battTick(int pct, boolean charging) {
        if (pct < 0) return;
        long now = System.currentTimeMillis();
        long day = today();
        boolean dirty = false;

        // 跨天：把昨天这一桶归档，今天重新开一桶。
        // 归档门槛也要看 msAll：只插着电用过、没掉过电的那一天 ms=0 但确实有使用。
        if (battDay != day) {
            if (battToday.seeded()
                    && (battToday.ms >= 60000L || battToday.msAll >= 60000L)) {
                battToday.day = battDay;
                battArch.add(battToday);
                while (battArch.size() > 6) battArch.remove(0);
            }
            battToday = new BattMath.Acc();
            // 必须在这里立刻起算：旧代码靠下面 today() 里的隐式 seed 补，
            // 而跨天当天若一直插着电，today() 根本不会被调用（charging 早退）；
            // 等拔电后再补 seed，就会把这天已经攒下的 msAll 清零。
            BattMath.seed(battToday, now, pct);
            battDay = day;
            dirty = true;
        }

        if (!battSeeded) {
            battSeeded = true;
            battCharging = charging;
            BattMath.seed(battToday, now, pct);
            if (!charging) BattMath.seed(battCharge, now, pct);
            // 全时段口径从这一天起算；已有数据时不要覆盖起算日
            if (!battAll.seeded()) BattMath.seed(battAll, now, pct);
            if (battFirst <= 0) battFirst = day;
            dirty = true;
        } else if (charging != battCharging) {
            battCharging = charging;
            if (charging) {
                battCharge = new BattMath.Acc();   // 插电：这一轮到此为止
            } else {
                BattMath.seed(battCharge, now, pct);   // 拔电：新一轮从这里起算
            }
            dirty = true;
        }

        // 全时段口径起算日。旧版本没有 btf 键、却已经把 bts=true 落过盘，
        // 升级后 !battSeeded 分支永远不进，battFirst 会一直是 0 →
        // 「全部」页显示「已统计 0 天 / 每月约 0 小时」。所以每次 tick 都兜一次底。
        if (battFirst <= 0) {
            long archFirst = 0;
            for (BattMath.Acc a : battArch) {
                if (a.day > 0 && (archFirst == 0 || a.day < archFirst)) archFirst = a.day;
            }
            battFirst = archFirst > 0 ? archFirst : day;
            dirty = true;
        }

        // 「含充电总时长」：无论是否插电，只要 App 在前台且采样间隔正常就累计。
        // 必须放在上面的跨天 / seed / 充电翻转之后（那些操作会替换 Acc 对象），
        // 又要放在 charging 早退之前——旧版正是在这里直接 return，
        // 插着电使用的时间既没进 ms（正确）也没进任何总时长（缺口）。
        // 总时长只在内存里累加，不额外触发落盘：ms 本来也是靠掉电变化或 5 分钟兜底写盘，
        // 每分钟 apply() 一次对墨水屏是纯粹的耗电，没必要为了 1 分钟精度多写 5 倍。
        BattMath.gross(battToday, now);
        if (battCharge.seeded()) BattMath.gross(battCharge, now);
        BattMath.gross(battAll, now);

        if (charging) {
            // 充电中「未充电使用时长」与掉电都不累计（这一轮到此为止），
            // 但上面的 gross() 已经把这段前台时间记进了含充电总时长。
            // 电量变化仍要在下一次采样被 today() 看到，靠「电量上升」结束上一轮
            //（App 没运行时同样成立）。
            if (dirty || now - battSavedAt > 5L * 60000L) saveBatt();
            return;
        }

        boolean ch = BattMath.today(battToday, now, pct);
        if (battCharge.seeded()) ch |= BattMath.charge(battCharge, now, pct);
        // 全时段用 today()：充电只挪基点、不清零（用掉的电是真实消耗）
        ch |= BattMath.today(battAll, now, pct);

        if (dirty || ch || now - battSavedAt > 5L * 60000L) saveBatt();
    }

    /** 近 7 天的每日使用量（柱子用）。 */
    public static final class DayPoint {
        public long day;
        public long ms;       // 未充电时长
        public long msAll;    // 含充电总时长

        public DayPoint(long d, long m, long a) { day = d; ms = m; msAll = a; }
    }

    /**
     * 近 7 天每天的使用时长，按日期升序返回（最多 7 条，最后一条是今天）。
     * 归档桶只留 6 天，加今天正好 7 天；缺的日子不补，返回的条数可能少于 7。
     */
    public ArrayList<DayPoint> batt7Daily() {
        ArrayList<DayPoint> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        for (int i = 6; i >= 0; i--) {
            long d = DayMath.offset(c, -i);
            long ms = 0, msAll = 0;
            // 今天这一桶的 day 字段从来没被赋值（跨天归档时写的是**旧**对象，
            // 新的 battToday 是 new Acc()，day=0），所以要用 battDay 判断今天。
            // 旧代码拿 battToday.day 比，等于把今天这一格永远画成 0。
            if (battDay == d) {
                ms = battToday.ms;
                msAll = battToday.msAll;
            } else {
                for (BattMath.Acc a : battArch) {
                    if (a.day == d) { ms = a.ms; msAll = a.msAll; break; }
                }
            }
            out.add(new DayPoint(d, ms, msAll));
        }
        return out;
    }

    /** 全时段累计使用时长（未充电）。 */
    public long battAllMs() { return battAll.ms; }

    /** 全时段累计前台总时长（含充电）。 */
    public long battAllMsAll() { return battAll.msAll; }

    /** 全时段累计掉电。 */
    public int battAllDrop() { return battAll.drop; }

    /** 全时段已经统计了多少天（含起算日，至少 1）。 */
    public int battAllDays() {
        if (battFirst <= 0) return 0;
        return (int) Math.max(1L, dayNum(today()) - dayNum(battFirst) + 1);
    }

    private static long dayNum(long ymd) {
        Calendar c = Calendar.getInstance();
        c.set((int) (ymd / 10000), (int) (ymd / 100 % 100) - 1, (int) (ymd % 100), 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 86400000L;
    }

    /** 近 7 天：归档日桶（6 天内）+ 今天的实时值。 */
    public long batt7Ms() {
        long s = battToday.ms;
        for (BattMath.Acc a : battArch) if (within7(a.day)) s += a.ms;
        return s;
    }

    /** 近 7 天含充电的前台总时长。 */
    public long batt7MsAll() {
        long s = battToday.msAll;
        for (BattMath.Acc a : battArch) if (within7(a.day)) s += a.msAll;
        return s;
    }

    public int batt7Drop() {
        int s = battToday.drop;
        for (BattMath.Acc a : battArch) if (within7(a.day)) s += a.drop;
        return s;
    }

    private static boolean within7(long day) {
        return BattMath.withinDays(day, today(), 7);
    }

    private void saveBatt() {
        battSavedAt = System.currentTimeMillis();
        try {
            JSONArray arch = new JSONArray();
            for (BattMath.Acc a : battArch) arch.put(accJson(a));
            p.edit()
                    .putString("bt1", accJson(battToday).toString())
                    .putString("bt2", accJson(battCharge).toString())
                    .putString("bt7", arch.toString())
                    .putString("bt9", accJson(battAll).toString())
                    .putLong("btf", battFirst)
                    .putLong("btd", battDay)
                    .putBoolean("btc", battCharging)
                    .putBoolean("bts", battSeeded)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject accJson(BattMath.Acc a) throws Exception {
        JSONObject o = new JSONObject();
        o.put("d", a.day);
        o.put("s", a.start);
        o.put("l", a.last);
        o.put("p", a.pct);
        o.put("f", a.from);
        o.put("m", a.ms);
        o.put("a", a.msAll);
        o.put("A", a.lastAll);
        o.put("r", a.drop);
        o.put("c", a.charged);
        return o;
    }

    private static BattMath.Acc accFrom(String s) {
        BattMath.Acc a = new BattMath.Acc();
        try {
            JSONObject o = new JSONObject(s);
            a.day = o.optLong("d", 0);
            a.start = o.optLong("s", 0);
            a.last = o.optLong("l", 0);
            a.pct = o.optInt("p", -1);
            a.from = o.optInt("f", -1);
            a.ms = o.optLong("m", 0);
            a.msAll = o.optLong("a", 0);
            a.lastAll = o.optLong("A", 0);
            a.drop = o.optInt("r", 0);
            a.charged = o.optBoolean("c", false);
            // 升级前没有 a / A 两个键：总时长至少要等于未充电时长
            BattMath.migrate(a);
        } catch (Exception ignored) {
        }
        return a;
    }

    private void loadBatt(String s1, String s2, String s7, long day, boolean charging, boolean seeded) {
        battArch.clear();
        if (s1 != null && !s1.isEmpty()) battToday = accFrom(s1);
        if (s2 != null && !s2.isEmpty()) battCharge = accFrom(s2);
        String s9 = p.getString("bt9", "");
        if (!s9.isEmpty()) battAll = accFrom(s9);
        battFirst = p.getLong("btf", 0);
        battDay = day;
        battCharging = charging;
        battSeeded = seeded;
        try {
            JSONArray arr = new JSONArray(s7 == null || s7.isEmpty() ? "[]" : s7);
            for (int i = 0; i < arr.length(); i++) battArch.add(accFrom(arr.getString(i)));
        } catch (Exception ignored) {
        }
    }

    public void clearAll() {
        todos.clear();
        habits.clear();
        days.clear();
        pomoDoneToday = 0;
        pomoDoneDay = today();
        pomoHist.clear();
        pomoAllRounds = 0;
        pomoAllMinutes = 0;
        habitArch.clear();
        habitArchDirty = true;
        battToday = new BattMath.Acc();
        battCharge = new BattMath.Acc();
        battAll = new BattMath.Acc();
        battFirst = 0;
        battArch.clear();
        battDay = today();
        battSavedAt = 0;
        battSeeded = false;
        battCharging = false;
        p.edit().remove("bt1").remove("bt2").remove("bt7").remove("bt9").remove("btf")
                .remove("btd").remove("btc").remove("bts").remove("btl").apply();
        // 清空后习惯 id 也归零重发，免得跟归档里的旧 id 撞上
        nextHabitId = 1;
        save();
    }

    /**
     * 打卡的唯一写入口：同时维护 days[] 与 mins[]，避免两处不同步。
     * 补记历史日期时记 UNKNOWN（用户当时没在场，编个时间反而是假数据）。
     */
    public void mark(Habit h, int day, boolean on) {
        if (h == null || day < 1 || day > 31) return;
        h.days[day] = on;
        if (!on) {
            h.mins[day] = HabitTime.NONE;
        } else if (day == dayOfMonth()) {
            Calendar c = Calendar.getInstance();
            h.mins[day] = HabitTime.encode(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } else {
            h.mins[day] = HabitTime.UNKNOWN;
        }
    }

    public void save() {
        int dom = dayOfMonth();
        for (Habit h : habits) {
            int n = 0, start = h.days[dom] ? dom : dom - 1;
            for (int d = start; d >= 1 && h.days[d]; d--) n++;
            h.streak = n;
        }
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (Todo t : todos) {
                JSONObject x = new JSONObject();
                x.put("t", t.text); x.put("d", t.done); x.put("dt", t.day);
                x.put("at", t.addAt); x.put("ft", t.doneAt);
                a.put(x);
            }
            o.put("todos", a);

            JSONArray hs = new JSONArray();
            for (Habit h : habits) {
                JSONObject x = new JSONObject();
                x.put("n", h.name); x.put("s", h.streak);
                // 习惯 id：稳定标识，改名后历史仍跟得上
                if (h.id == null || h.id.isEmpty()) h.id = newHabitId();
                x.put("i", h.id);
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= 31; i++) b.append(h.days[i] ? '1' : '0');
                x.put("b", b.toString());
                // 打卡时刻：老版本没有这个键，读的时候按位串迁移
                x.put("m", HabitTime.encodeArr(h.mins));
                hs.put(x);
            }
            o.put("habits", hs);

            JSONArray ds = new JSONArray();
            for (Day d : days) {
                JSONObject x = new JSONObject();
                x.put("n", d.name); x.put("m", d.millis);
                x.put("p", d.pinned); x.put("c", d.onClock);
                ds.put(x);
            }
            o.put("days", ds);

            android.content.SharedPreferences.Editor ed = p.edit()
                    .putString("data", o.toString())
                    .putBoolean("sec", showSeconds)
                    .putBoolean("h24", h24)
                    .putBoolean("lunar", lunar)
                    .putBoolean("keep", keepOn)
                    .putBoolean("oled", oled)
                    .putFloat("fs", fontScale)
                    .putInt("pf", pomo.focus)
                    .putInt("ps", pomo.shortBreak)
                    .putInt("pl", pomo.longBreak)
                    .putInt("pr", pomo.rounds)
                    .putInt("pd", pomoDoneToday)
                    .putLong("pdd", pomoDoneDay)
                    .putString("ph", encodePomoHist())
                    .putInt("par", pomoAllRounds)
                    .putInt("pam", pomoAllMinutes)
                    .putInt("hm", habitMonth)
                    .putInt("hi", nextHabitId);
            // 打卡归档：只在这个月真的翻过去时才写，避免每次打卡都重写一遍大字符串
            if (habitArchDirty) {
                ed.putString("ha", HabitArchive.encode(habitArch));
                habitArchDirty = false;
            }
            ed.apply();
            idDirty = false;
        } catch (Exception ignored) {
        }
    }

    /**
     * 让 id 计数器超过现存的所有 id。
     * 恢复备份后必须重算：备份里的习惯带着旧 id，计数器若停在 1，
     * 新加的习惯就会拿到 h1，和老习惯撞上，历史会串。
     */
    private void syncNextHabitId() {
        int max = 0;
        for (Habit h : habits) max = Math.max(max, HabitIds.num(h.id));
        for (HabitArchive.Month m : habitArch) {
            for (HabitArchive.Entry e : m.entries) max = Math.max(max, HabitIds.num(e.id));
        }
        nextHabitId = Math.max(nextHabitId, max + 1);
    }

    /** 读回打卡归档。 */
    private void loadHabitArch(String s) {
        habitArch.clear();
        try {
            habitArch.addAll(HabitArchive.decode(s));
        } catch (Exception ignored) {
            habitArch.clear();
        }
    }
}
