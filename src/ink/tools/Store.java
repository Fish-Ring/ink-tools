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
        public String name;
        public int streak;
        public final boolean[] days = new boolean[32];
        public Habit(String n) { name = n; }
    }

    public static final class Day {
        public String name;
        public long millis;
        public boolean pinned;                 // 置顶到台历右上角，全列表唯一
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

    // ---------------------------------------------------------------- 电量统计
    /**
     * 一个统计口径的累加器。只存「当前这一次」，不留历史明细。
     *
     * start 是这一轮从几点起算，last 是上一次采样时刻，pct 是上一次采样到的电量，
     * ms / drop 是累计的「有效使用时长」和「有效掉电」。
     *
     * 累计只在两次采样间隔不超过 BATT_GAP 时进行：超过就判定中间离开了，
     * 时长与掉电都不加，只把 last / pct 挪到当前。挪 pct 这一步最关键——
     * 离开一晚掉了 20%，若不挪，回来第一分钟就会把这 20% 算成消耗。
     */
    public static final class BattAcc {
        public long day;                 // 归档日桶用：yyyyMMdd
        public long start, last;
        public int pct = -1;
        public int from = -1;            // 起点电量，用于「90% → 87%」这种展示
        public long ms;
        public int drop;
    }

    /** 两次采样间隔超过这个值就算离开。tick 对齐整分钟，正常间隔 60.02 秒，留足抖动余量。 */
    public static final long BATT_GAP = 100000L;

    public BattAcc battToday = new BattAcc();      // 今天
    public BattAcc battCharge = new BattAcc();     // 本次充电后（拔电起算）
    public final ArrayList<BattAcc> battArch = new ArrayList<>();   // 归档日桶，最多 6 条
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
        if (rolloverIfNeeded()) save();
    }

    /** yyyyMMdd，用于「今日」判定与跨天重置统计。 */
    public static long today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000L
                + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
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
                for (int i = 0; i < hs.length(); i++) {
                    JSONObject x = hs.getJSONObject(i);
                    Habit h = new Habit(x.optString("n", ""));
                    h.streak = x.optInt("s", 0);
                    String bits = x.optString("b", "");
                    for (int k = 0; k < 31 && k < bits.length(); k++) {
                        h.days[k + 1] = bits.charAt(k) == '1';
                    }
                    habits.add(h);
                }
            }
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) {
                boolean seen = false;
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject x = ds.getJSONObject(i);
                    Day d = new Day(x.optString("n", ""), x.optLong("m", 0));
                    boolean want = x.optBoolean("p", false) && !seen;
                    d.pinned = want;
                    if (want) seen = true;
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

    /** 置顶的唯一写入口：先清全部，再按对象身份切换（不受增删导致的下标漂移影响）。 */
    public void togglePin(Day d) {
        if (d == null || days.indexOf(d) < 0) return;
        boolean on = !d.pinned;
        for (Day x : days) x.pinned = false;
        d.pinned = on;
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

    /** 跨天重置今日番茄轮数；跨月清空打卡格。返回是否有变化。 */
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
                for (Habit h : habits) java.util.Arrays.fill(h.days, false);
            }
            habitMonth = hm;
            changed = true;
        }
        return changed;
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
            if (hs != null) {
                for (int i = 0; i < hs.length(); i++) {
                    JSONObject x = hs.getJSONObject(i);
                    Habit h = new Habit(x.optString("n", ""));
                    h.streak = x.optInt("s", 0);
                    String bits = x.optString("b", "");
                    for (int k = 0; k < 31 && k < bits.length(); k++) h.days[k + 1] = bits.charAt(k) == '1';
                    nh.add(h);
                }
            }

            ArrayList<Day> nd = new ArrayList<>();
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) {
                boolean seen = false;
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject x = ds.getJSONObject(i);
                    Day d = new Day(x.optString("n", ""), x.optLong("m", 0));
                    if (x.optBoolean("p", false) && !seen) { d.pinned = true; seen = true; }
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
     * 三条口径共用同一套累加：今日、本次充电后、近 7 天（归档日桶 + 今日）。
     * 不记明细、不新增定时器——电量本身只有 1% 粒度，写得再密也没有多余信息。
     */
    public void battTick(int pct, boolean charging) {
        if (pct < 0) return;
        long now = System.currentTimeMillis();
        long day = today();
        boolean dirty = false;

        // 跨天：把昨天这一桶归档，今天重新开一桶
        if (battDay != day) {
            if (battToday.pct >= 0 && battToday.ms >= 60000L) {
                battToday.day = battDay;
                battArch.add(battToday);
                while (battArch.size() > 6) battArch.remove(0);
            }
            battToday = new BattAcc();
            battDay = day;
            dirty = true;
        }

        if (!battSeeded) {
            battSeeded = true;
            battCharging = charging;
            seed(battToday, now, pct);
            if (!charging) seed(battCharge, now, pct);
            dirty = true;
        } else if (charging != battCharging) {
            battCharging = charging;
            if (charging) battCharge = new BattAcc();     // 插电：这一轮到此为止
            else seed(battCharge, now, pct);              // 拔电：新一轮从这里起算
            dirty = true;
        }

        if (charging) {
            if (dirty || now - battSavedAt > 5L * 60000L) saveBatt();
            return;
        }

        boolean dropCh = step(battToday, now, pct);
        if (battCharge.pct >= 0) dropCh |= step(battCharge, now, pct);

        if (dirty || dropCh || now - battSavedAt > 5L * 60000L) saveBatt();
    }

    private static void seed(BattAcc a, long now, int pct) {
        a.start = now;
        a.last = now;
        a.pct = pct;
        a.from = pct;
        a.ms = 0;
        a.drop = 0;
    }

    /** 累计一段，返回累计值是否变了。 */
    private static boolean step(BattAcc a, long now, int pct) {
        if (a.pct < 0) { seed(a, now, pct); return true; }
        long gap = now - a.last;
        if (gap < 0 || gap > BATT_GAP) {
            // 判定离开：不累加，只滑动基点。start 保持不动，
            // 这样「累计 60 分」和「从 10:30 起算」可以同时成立。
            a.last = now;
            a.pct = pct;
            return true;
        }
        // 时长每分钟都在涨，不能拿它当写盘信号，否则一天要写 1440 次。
        // 只有「掉电变了」才立刻落盘，时长靠 5 分钟的兜底保存 —— 进程被杀最多丢 5 分钟。
        boolean ch = false;
        if (gap >= 1500L) a.ms += gap;
        if (pct < a.pct) { a.drop += a.pct - pct; ch = true; }
        a.last = now;
        a.pct = pct;
        return ch;
    }

    /** 近 7 天：归档日桶（6 天内）+ 今天的实时值。 */
    public long batt7Ms() {
        long s = battToday.ms;
        for (BattAcc a : battArch) if (within7(a.day)) s += a.ms;
        return s;
    }

    public int batt7Drop() {
        int s = battToday.drop;
        for (BattAcc a : battArch) if (within7(a.day)) s += a.drop;
        return s;
    }

    private static boolean within7(long day) {
        // yyyyMMdd 直接相减在跨月时是错的（20261101 - 20261031 = 70），先换成天序号
        long t = today();
        if (day <= 0 || day > t) return false;
        return dayNum(t) - dayNum(day) <= 6;
    }

    private static long dayNum(long ymd) {
        Calendar c = Calendar.getInstance();
        c.set((int) (ymd / 10000), (int) (ymd / 100 % 100) - 1, (int) (ymd % 100), 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 86400000L;
    }

    private void saveBatt() {
        battSavedAt = System.currentTimeMillis();
        try {
            JSONArray arch = new JSONArray();
            for (BattAcc a : battArch) arch.put(accJson(a));
            p.edit()
                    .putString("bt1", accJson(battToday).toString())
                    .putString("bt2", accJson(battCharge).toString())
                    .putString("bt7", arch.toString())
                    .putLong("btd", battDay)
                    .putBoolean("btc", battCharging)
                    .putBoolean("bts", battSeeded)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject accJson(BattAcc a) throws Exception {
        JSONObject o = new JSONObject();
        o.put("d", a.day);
        o.put("s", a.start);
        o.put("l", a.last);
        o.put("p", a.pct);
        o.put("f", a.from);
        o.put("m", a.ms);
        o.put("r", a.drop);
        return o;
    }

    private static BattAcc accFrom(String s) {
        BattAcc a = new BattAcc();
        try {
            JSONObject o = new JSONObject(s);
            a.day = o.optLong("d", 0);
            a.start = o.optLong("s", 0);
            a.last = o.optLong("l", 0);
            a.pct = o.optInt("p", -1);
            a.from = o.optInt("f", -1);
            a.ms = o.optLong("m", 0);
            a.drop = o.optInt("r", 0);
        } catch (Exception ignored) {
        }
        return a;
    }

    private void loadBatt(String s1, String s2, String s7, long day, boolean charging, boolean seeded) {
        if (s1 != null && !s1.isEmpty()) battToday = accFrom(s1);
        if (s2 != null && !s2.isEmpty()) battCharge = accFrom(s2);
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
        battToday = new BattAcc();
        battCharge = new BattAcc();
        battArch.clear();
        battDay = today();
        battSeeded = false;
        battCharging = false;
        p.edit().remove("bt1").remove("bt2").remove("bt7")
                .remove("btd").remove("btc").remove("bts").remove("btl").apply();
        save();
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
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= 31; i++) b.append(h.days[i] ? '1' : '0');
                x.put("b", b.toString());
                hs.put(x);
            }
            o.put("habits", hs);

            JSONArray ds = new JSONArray();
            for (Day d : days) {
                JSONObject x = new JSONObject();
                x.put("n", d.name); x.put("m", d.millis); x.put("p", d.pinned);
                ds.put(x);
            }
            o.put("days", ds);

            p.edit()
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
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
