package ink.tools;

/**
 * 电量累加的核心算法：纯逻辑，不碰 Android API，可以在桌面 JVM 上直接单测。
 *
 * 一条记录（Acc）描述「一个统计口径」的当前状态：起点时刻、上次采样时刻、上次电量、
 * 累计使用时长、累计掉电、充电次数。
 *
 * 两条硬规则：
 *
 * 1. 两次采样间隔超过 {@link #GAP_MS} 视为「离开」，既不计时长也不计掉电，
 *    只把基点挪到当前。挪基点这一步最关键——离开一晚掉了 20%，若不挪，
 *    回来第一分钟就会把这 20% 算成前台消耗。
 *
 * 2. 电量**上升**一律判定为「中间充过电」，哪怕 App 期间完全没有运行。
 *    没有后台服务的前提下，这是唯一能发现充电的信号：插着电充到满再拔掉，
 *    下次打开时看到 pct 比上次高，就知道这一轮早该结束了。
 *
 * 早先只在「充电状态标志翻转」时重置，所以「充电时没开 App」的情况完全记不到，
 * 「本次充电后」会一直沿用充电前的旧起点。
 */
public final class BattMath {

    /** 两次采样间隔超过这个值就算离开。tick 对齐整分钟，正常间隔 60.02 秒，留足抖动余量。 */
    public static final long GAP_MS = 100000L;

    /** 小于这个间隔的同分重绘不计入使用时长（开秒时每秒都会 tick）。 */
    private static final long MIN_STEP_MS = 1500L;

    public static final class Acc {
        public long day;             // 归档日桶用：yyyyMMdd
        public long start;           // 这一轮从几点起算
        public long last;            // 上一次采样时刻（未充电口径）
        public int pct = -1;         // 上一次采样到的电量
        public int from = -1;        // 本轮起点电量
        public long ms;              // 累计有效使用时长（**未充电**）
        public long msAll;           // 累计前台总时长（含充电时段）
        public long lastAll;         // msAll 独立的上次采样时刻
        public int drop;             // 累计掉电
        public boolean charged;      // 本口径内是否检测到过充电（电量上升即判定）

        public boolean seeded() { return pct >= 0; }
    }

    private BattMath() {}

    /** 从现在、当前电量重新起算。 */
    public static void seed(Acc a, long now, int pct) {
        a.start = now;
        a.last = now;
        a.pct = pct;
        a.from = pct;
        a.ms = 0;
        a.drop = 0;
        a.charged = false;
        // 总时长是另一套口径，新的一轮同样从零起算；但 lastAll 不能清零，
        // 否则充电状态翻转的那一分钟会被凭空多算一次。
        a.msAll = 0;
        if (a.lastAll <= 0) a.lastAll = now;
    }

    /**
     * 「含充电总时长」：不管有没有在充电，只要 App 在前台走一个 tick 就累计。
     *
     * 与 {@link #today} / {@link #charge} 完全独立，用自己的时间戳判断离开，
     * 这样充电期间既不会污染 ms / drop，也不会漏掉「插着电也在用」的时间。
     * 返回是否发生变化，供调用方决定要不要落盘。
     */
    public static boolean gross(Acc a, long now) {
        long prev = a.lastAll;
        if (prev <= 0) {                            // 首次：只对齐基点
            a.lastAll = now;
            return true;
        }
        long gap = now - prev;
        if (gap < 0) return false;                  // 时间倒流：基点保持不动，别被回拨
        if (gap > GAP_MS) {                         // 离开：滑基点、不计时
            a.lastAll = now;
            return false;
        }
        a.lastAll = now;
        if (gap >= MIN_STEP_MS) {
            a.msAll += gap;
            return true;
        }
        return false;
    }

    /**
     * 旧数据没有 msAll 字段：总时长至少要等于已记下的未充电时长，
     * 否则升级后会显示「总 0 分钟 / 未充电 3 小时」这种自相矛盾的数字。
     */
    public static void migrate(Acc a) {
        if (a.msAll < a.ms) a.msAll = a.ms;
        if (a.lastAll <= 0) a.lastAll = a.last;
    }

    /**
     * 「今日」口径：充电不中断当日累计（前面用掉的电是真实的），
     * 但要把起点电量挪到充电后，并记一次充电——否则会出现「耗电 50%（90% → 89%）」。
     *
     * @return 累计值是否发生变化（调用方据此决定是否落盘）
     */
    public static boolean today(Acc a, long now, int pct) {
        if (pct < 0) return false;
        if (!a.seeded()) { seed(a, now, pct); return true; }
        if (pct > a.pct) {
            a.charged = true;
            a.from = pct;
            a.last = now;
            a.pct = pct;
            return true;
        }
        return discharge(a, now, pct);
    }

    /**
     * 「本次充电后」口径：电量上升说明上一轮已经结束，从充电后的电量重新起算。
     *
     * 起点时刻只能记成「检测到的时刻」，因为充电发生时 App 可能没运行。
     */
    public static boolean charge(Acc a, long now, int pct) {
        if (pct < 0) return false;
        if (!a.seeded()) { seed(a, now, pct); return true; }
        if (pct > a.pct) {
            seed(a, now, pct);
            return true;
        }
        return discharge(a, now, pct);
    }

    /** 放电 / 静置：正常累计；间隔过大只滑基点。 */
    private static boolean discharge(Acc a, long now, int pct) {
        long gap = now - a.last;
        if (gap < 0) {                 // 系统时间被回拨：别把基点也回拨，也别计负时长
            a.pct = pct;
            return true;
        }
        if (gap > GAP_MS) {
            a.last = now;
            a.pct = pct;
            return true;
        }
        boolean changed = false;
        if (gap >= MIN_STEP_MS) a.ms += gap;
        if (pct < a.pct) {
            a.drop += a.pct - pct;
            changed = true;
        }
        a.last = now;
        a.pct = pct;
        return changed;
    }

    /** 折算 %/小时；样本不足 5 分钟不给（1% 粒度下那个数字会离谱）。返回 -1 表示不显示。 */
    public static double perHour(int drop, long ms) {
        double hours = ms / 3600000.0;
        if (hours < 0.08) return -1;
        return drop / hours;
    }

    /** 近 7 天判定：day 是 yyyyMMdd，按天序号比较，跨月不会算错。 */
    public static boolean withinDays(long day, long todayYmd, int days) {
        if (day <= 0 || day > todayYmd) return false;
        return dayNum(todayYmd) - dayNum(day) < days;
    }

    /** yyyyMMdd -> 天序号。用整数算法，不依赖 Calendar，纯函数好测。 */
    static long dayNum(long ymd) {
        long y = ymd / 10000L, m = ymd / 100L % 100L, d = ymd % 100L;
        // 公历转儒略日（Fliegel–Van Flandern）
        long a = (14 - m) / 12;
        long yy = y + 4800 - a;
        long mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
    }
}
