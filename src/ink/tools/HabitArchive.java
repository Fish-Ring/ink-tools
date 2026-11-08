package ink.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 打卡历史的「按月归档」。
 *
 * 背景：习惯的 days[] / mins[] 是**当月** 32 位数组，跨月会被清空，
 * 于是上个月的打卡记录在磁盘上根本不存在——不是没显示，是数据没了。
 * 要能按月回看历史，就必须把每个月结束时的位图另存一份。
 *
 * 身份用习惯 id 而不是名字：改名后历史要跟着新名字走，用名字做键会碎成两段。
 *
 * 存储格式：一个月一行，行内用空格分隔；习惯名带长度前缀，避免名字里出现空格导致解析错位。
 *   行 = ym 空格 数量 空格 (id 空格 名字长度:名字 空格 位串 空格 时刻串)*
 *   位串 = 31 个 '0'/'1'，第 i 个对应 i+1 日
 *   时刻串 = HabitTime.encodeArr 格式（31 个逗号分隔的数）
 *
 * **归档里只留「已经结束的月」，当前月永远是活的**（当前月的位图在 habits.days[] 里）。
 * 系统时间往回拨又拨回来时（11 月 → 10 月 → 11 月）同一个月会被切成两段，
 * 归档里可能躺着「当前月」的数据——这时要把它并回活位图（restoreInto / reconcileMonth），
 * 否则同一个月的两处数据会不一致：打卡页说「本月 0 次」、记录页说当月有记录。
 *
 * 纯逻辑，不 import android.*，桌面 JVM 可直接单测。
 */
public final class HabitArchive {

    /** 归档里的一条习惯记录（某月）。 */
    public static final class Entry {
        public String id = "";        // 习惯 id，改名不影响归档归属
        public String name = "";      // 归档时的习惯名（习惯已被删时用它兜底）
        public String bits = "";      // 31 个 '0'/'1'，下标 0 对应 1 日
        public String mins = "";      // HabitTime.encodeArr 格式

        public boolean marked(int day) {
            if (day < 1 || day > 31) return false;
            int i = day - 1;
            return i < bits.length() && bits.charAt(i) == '1';
        }

        /** 第 day 天的打卡时刻文本（空串 = 未打卡或时间未知）。 */
        public String timeOf(int day, int[] minsArr) {
            if (day < 1 || day > 31) return "";
            if (minsArr != null && day < minsArr.length) return HabitTime.hhmm(minsArr[day]);
            return "";
        }
    }

    /**
     * 活位图的一个槽位：一个习惯当月的打卡位图。
     *
     * 归档（字符串格式）与活位图（Store.Habit，依赖 android.*）之间需要一个
     * 中间形态，才能让「把归档里的当月并回活位图」这件事在纯 JVM 上可单测。
     */
    public static final class Slot {
        public String id = "";                 // 习惯 id
        public String name = "";               // 习惯名（归档时留一份，习惯被删时兜底显示）
        public final boolean[] days = new boolean[32];
        public final int[] mins = new int[32]; // HabitTime 编码：0 未打卡 / -1 未知 / >0 时刻
    }

    /** 归档里的一个月。 */
    public static final class Month {
        public int ym;                          // yyyyMM
        public final ArrayList<Entry> entries = new ArrayList<>();

        public Entry find(String id) {
            for (Entry e : entries) if (e.id.equals(id)) return e;
            return null;
        }
    }

    /** 归档最多保留多少个月（2 年）。再多对墨水屏上的翻页没有意义，还会撑大存储。 */
    public static final int MAX_MONTHS = 24;

    private HabitArchive() {
    }

    // ---------------------------------------------------------------- 编解码

    public static String encode(ArrayList<Month> months) {
        if (months == null || months.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (Month m : months) {
            if (b.length() > 0) b.append('\n');
            b.append(m.ym).append(' ').append(m.entries.size());
            for (Entry e : m.entries) {
                b.append(' ').append(e.id.isEmpty() ? "-" : e.id)
                        .append(' ').append(e.name.length()).append(':').append(e.name)
                        .append(' ').append(e.bits).append(' ').append(e.mins);
            }
        }
        return b.toString();
    }

    public static ArrayList<Month> decode(String s) {
        ArrayList<Month> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            if (line.trim().isEmpty()) continue;
            Cursor c = new Cursor(line);
            Month m = new Month();
            m.ym = c.intToken();
            int n = c.intToken();
            // 坏数据的上限：一条月记录最多几百个习惯，超了直接停，避免越界分配
            if (n < 0 || n > 500) n = 0;
            for (int i = 0; i < n; i++) {
                Entry e = new Entry();
                e.id = c.token();
                if (e.id.equals("-")) e.id = "";
                int len = c.intToken();          // 读到 ':' 为止
                if (len < 0 || len > 200) len = 0;
                e.name = c.take(len);
                e.bits = c.token();
                e.mins = c.token();
                m.entries.add(e);
            }
            out.add(m);
        }
        return out;
    }

    /** 简易游标：按空格切词，但支持「长度:内容」这种带长度的字段。 */
    private static final class Cursor {
        private final String s;
        private int p;

        Cursor(String s) { this.s = s; }

        private void skip() {
            while (p < s.length() && s.charAt(p) == ' ') p++;
        }

        String token() {
            skip();
            int st = p;
            while (p < s.length() && s.charAt(p) != ' ') p++;
            return s.substring(st, p);
        }

        /**
         * 读整数。遇到 ':' 就停并把它吃掉（长度前缀的格式是「长度:内容」），
         * 后面跟空格则不动。
         */
        int intToken() {
            skip();
            int st = p;
            while (p < s.length() && s.charAt(p) != ' ' && s.charAt(p) != ':') p++;
            int v;
            try {
                v = Integer.parseInt(s.substring(st, p));
            } catch (NumberFormatException e) {
                v = 0;
            }
            if (p < s.length() && s.charAt(p) == ':') p++;     // 吃掉 ':'
            return v;
        }

        /**
         * 取固定长度的字符（名字里可能有空格，也可能以空格开头）。
         *
         * 起点就是当前游标位置：调用方 intToken() 刚吃完 ':'，正好落在字段内容的
         * 第一个字符上。**不能像 token() 那样先跳空格**——名字本身以空格开头时
         * （" 早睡"）那个空格是内容的一部分，跳掉就整体错位，后面位串/时刻串全串位。
         * 空格只在字段分隔处才有意义，字段内容的长度是已知的，按长度精确取即可。
         */
        String take(int len) {
            if (p >= s.length() || len <= 0) return "";
            int end = Math.min(s.length(), p + len);
            String r = s.substring(p, end);
            p = end;
            return r;
        }
    }

    // ---------------------------------------------------------------- 合并与裁剪

    /**
     * 深拷贝一份月份列表：改副本不会动到存档本体。
     *
     * 合并活月前必须先拷贝：存档里的 Month/Entry 对象是长期持有的，
     * 直接往里并等于把「还在打卡的当月」提前写进存档，下一次跨月再归档就会重复。
     */
    public static ArrayList<Month> copy(ArrayList<Month> months) {
        ArrayList<Month> out = new ArrayList<>();
        if (months == null) return out;
        for (Month m : months) {
            Month c = new Month();
            c.ym = m.ym;
            for (Entry e : m.entries) {
                Entry x = new Entry();
                x.id = e.id;
                x.name = e.name;
                x.bits = e.bits;
                x.mins = e.mins;
                c.entries.add(x);
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 存档 + 活的当前月 → 记录页用的月份视图：同月取并集，月份倒序。
     *
     * 同月**不替换**而是合并：系统时间往回拨又拨回来时，同一个月会被切成两段
     * （11 月 → 10 月 → 11 月），两段都是真实打卡，只留活的那段就是丢数据。
     * 合并后同一个月仍然只有一条 Month，记录页不会把它当成两个月。
     */
    public static ArrayList<Month> view(ArrayList<Month> arch, Month live) {
        ArrayList<Month> out = copy(arch);
        merge(out, live);              // 复用同一套合并语义：同月同 id 按位取并集
        sortDesc(out);
        return out;
    }

    /**
     * 把一个月并进已有归档：同月则按位取并集（任一处打过就算打过），
     * 时刻取更「真」的那个（真实时刻 > UNKNOWN > 未打卡）。
     * 这样「系统时间往回拨又拨回来」也不会丢记录，
     * 反而能把同一月的两次记录合并成一份。
     */
    public static void merge(ArrayList<Month> months, Month m) {
        if (m == null) return;
        for (Month x : months) {
            if (x.ym != m.ym) continue;
            for (Entry e : m.entries) {
                Entry old = x.find(e.id);
                if (old == null) {
                    x.entries.add(e);
                } else {
                    old.bits = orBits(old.bits, e.bits);
                    old.mins = mergeMins(old.mins, e.mins);
                    if (old.name.isEmpty()) old.name = e.name;
                }
            }
            return;
        }
        months.add(m);
    }

    /** 位串并集，长度不同按短的补 0。 */
    public static String orBits(String a, String b) {
        int n = Math.max(a == null ? 0 : a.length(), b == null ? 0 : b.length());
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char ca = a != null && i < a.length() ? a.charAt(i) : '0';
            char cb = b != null && i < b.length() ? b.charAt(i) : '0';
            r.append(ca == '1' || cb == '1' ? '1' : '0');
        }
        return r.toString();
    }

    /**
     * 时刻串合并：取更「真」的那个（真实时刻 > UNKNOWN > 未打卡）。
     *
     * 不能写成「a 非零就取 a」：a = -1（补记 / 老数据迁移）也是非零，
     * 那样后一段记的真实时刻会被丢掉，只剩打卡标记没有时刻。
     */
    public static String mergeMins(String a, String b) {
        int[] ia = HabitTime.decode(a);
        int[] ib = HabitTime.decode(b);
        int[] r = new int[32];
        for (int i = 1; i < 32; i++) r[i] = betterMin(ia[i], ib[i]);
        return HabitTime.encodeArr(r);
    }

    /**
     * 两个时刻取值里挑更「真」的一个。
     *
     * 编码：0 = 未打卡，-1 = 打过卡但没时间（补记 / 老数据迁移），>0 = 真实时刻。
     * 优先级：真实时刻 > UNKNOWN > 未打卡。
     */
    public static int betterMin(int a, int b) {
        if (a > 0) return a;          // a 是真实时刻，最优先
        if (b > 0) return b;          // b 是真实时刻
        if (a != 0) return a;         // a 是 UNKNOWN(-1)，比未打卡强
        return b;                      // 走到这里 a 必是 0，b 可能是 -1 或 0
    }

    // ------------------------------------------------------- 恢复：归档 → 活位图

    /**
     * 把活位图打包成某个月的归档记录（每个习惯一条，没打卡的也留一条空记录）。
     *
     * 与 restoreInto 正好是一对：snapshot 是「活 → 归档」，restoreInto 是「归档 → 活」。
     */
    public static Month snapshot(int ym, List<Slot> slots) {
        Month m = new Month();
        m.ym = ym;
        if (slots == null) return m;
        for (Slot s : slots) {
            if (s == null) continue;
            Entry e = new Entry();
            e.id = s.id == null ? "" : s.id;
            e.name = s.name == null ? "" : s.name;
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= 31; i++) b.append(s.days[i] ? '1' : '0');
            e.bits = b.toString();
            e.mins = HabitTime.encodeArr(s.mins);
            m.entries.add(e);
        }
        return m;
    }

    /**
     * 把归档里某个月的数据并回活位图（按位取并集）。
     *
     * 归档的语义是「**已经结束的月**」，当前月永远是活的。系统时间往回拨又拨回来时，
     * 同一个月会被切成两段（11 月 → 10 月 → 11 月）：归档里躺着第一段、活位图是第二段。
     * 跨月时活位图被清空，归档里就只剩当前月的数据而活位图是空的——
     * 打卡页显示「本月 0 次」、记录页却显示当月有记录，两页自相矛盾。
     * 所以进到某个月时，必须把归档里该月的数据并回活位图。
     *
     * 合并语义与 merge() 一致：
     * - 位图取并集（任一处打过就算打过），活位图里用户新打的卡不会被覆盖；
     * - 时刻取更「真」的那个（真实时刻 > UNKNOWN > 未打卡）。
     *
     * 归档里某个习惯 id 在活习惯里已经不存在（习惯被删了）时跳过它——
     * 没有槽位可并进去。
     *
     * @return true = 活位图有变化
     */
    public static boolean restoreInto(Month m, List<Slot> slots) {
        if (m == null || slots == null || slots.isEmpty()) return false;
        boolean changed = false;
        for (Entry e : m.entries) {
            if (e == null || e.id == null || e.id.isEmpty()) continue;
            Slot slot = findSlot(slots, e.id);
            if (slot == null) continue;                 // 习惯已删除：没有槽位，跳过
            int[] amins = HabitTime.decode(e.mins);
            for (int d = 1; d <= 31; d++) {
                if (e.marked(d) && !slot.days[d]) { slot.days[d] = true; changed = true; }
                if (!slot.days[d]) continue;            // 那天没打卡，谈时刻没有意义
                int av = d < amins.length ? amins[d] : HabitTime.NONE;
                int lv = slot.mins[d];
                int merged = betterMin(lv, av);
                if (merged != lv) { slot.mins[d] = merged; changed = true; }
            }
        }
        return changed;
    }

    /**
     * 对账：归档里若还有「当前月」这一条，把它并回活位图，并从归档里移除。
     *
     * 移除而不是留着靠读时合并：归档的语义是「已经结束的月」，
     * 留着当前月就会出现「同一个月的两处数据不一致」——
     * 活位图被清空、归档里却还写着打卡记录，用户看着像记录丢了。
     * 移走之后这个月的数据由活位图独家持有，下次跨月再归档一次即可。
     *
     * @param arch  归档（就地修改：移除 ym 那一条）
     * @param ym    当前月键 yyyyMM
     * @param slots 活位图槽位（就地修改）
     * @return true = 归档里有当前月，已并回活位图并移除
     */
    public static boolean reconcileMonth(ArrayList<Month> arch, int ym, List<Slot> slots) {
        if (arch == null || ym <= 0) return false;
        Month m = null;
        for (Month x : arch) {
            if (x != null && x.ym == ym) { m = x; break; }
        }
        if (m == null) return false;                   // 归档里没有当月：什么都不动
        restoreInto(m, slots);
        arch.remove(m);
        return true;
    }

    /** 按习惯 id 找槽位。 */
    private static Slot findSlot(List<Slot> slots, String id) {
        for (Slot s : slots) {
            if (s != null && id.equals(s.id)) return s;
        }
        return null;
    }

    /** 按 ym 从大到小排（新的在前）。 */
    public static void sortDesc(ArrayList<Month> months) {
        if (months == null) return;
        java.util.Collections.sort(months, new java.util.Comparator<Month>() {
            @Override
            public int compare(Month a, Month b) {
                return a.ym < b.ym ? 1 : (a.ym > b.ym ? -1 : 0);
            }
        });
    }

    /** 只保留最近 MAX_MONTHS 个月（按 ym 从大到小排，砍掉最旧的）。 */
    public static void trim(ArrayList<Month> months) {
        if (months == null) return;
        sortDesc(months);
        while (months.size() > MAX_MONTHS) months.remove(months.size() - 1);
    }

    /** 该月有多少天打卡（全部习惯合计）。 */
    public static int totalDays(Month m) {
        int n = 0;
        for (Entry e : m.entries) {
            for (int i = 0; i < e.bits.length(); i++) if (e.bits.charAt(i) == '1') n++;
        }
        return n;
    }
}
