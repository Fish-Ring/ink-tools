package ink.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 打卡历史归档 + 记录分组的单测。
 *
 * 这两块是本轮改动里最容易出错的地方：
 * - 归档格式是自定义字符串，编解码必须能原样还原；
 * - 跨月归档后，同一个月再次归档要合并而不是重复；
 * - 记录分页时任何一行都不能丢，且每条明细都要能看出属于哪个分组。
 */
public class HabitArchiveTest {

    private static int pass = 0, fail = 0;

    private static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static void eq(String what, long got, long want) {
        if (got == want) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + " (得到 " + got + "，期望 " + want + ")"); }
    }

    private static void eqs(String what, String got, String want) {
        if (got.equals(want)) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + " (得到 \"" + got + "\"，期望 \"" + want + "\")"); }
    }

    public static void main(String[] a) {
        // ---------------------------------------------------------- 归档编解码
        HabitArchive.Month m = new HabitArchive.Month();
        m.ym = 202611;
        HabitArchive.Entry e = new HabitArchive.Entry();
        e.id = "h1";
        e.name = "早睡";
        e.bits = "1010000000000000000000000000000";
        e.mins = HabitTime.encodeArr(new int[32]);
        m.entries.add(e);
        HabitArchive.Entry e2 = new HabitArchive.Entry();
        e2.id = "h2";
        e2.name = "有 空格 的名字";
        e2.bits = "0100000000000000000000000000000";
        e2.mins = HabitTime.encodeArr(new int[32]);
        m.entries.add(e2);

        ArrayList<HabitArchive.Month> one = new ArrayList<>();
        one.add(m);
        String enc = HabitArchive.encode(one);
        ArrayList<HabitArchive.Month> dec = HabitArchive.decode(enc);
        eq("编解码后月份数", dec.size(), 1);
        eq("月份键", dec.get(0).ym, 202611);
        eq("习惯条数", dec.get(0).entries.size(), 2);
        eqs("习惯 id", dec.get(0).entries.get(0).id, "h1");
        eqs("习惯名（含空格）", dec.get(0).entries.get(1).name, "有 空格 的名字");
        eqs("位串原样", dec.get(0).entries.get(0).bits, e.bits);
        ok("第 1 天标记正确", dec.get(0).entries.get(0).marked(1));
        ok("第 2 天未标记", !dec.get(0).entries.get(0).marked(2));
        ok("第 3 天标记正确", dec.get(0).entries.get(0).marked(3));
        ok("第 31 天越界不炸", !dec.get(0).entries.get(0).marked(31));

        // 空串 / 坏数据
        eq("空串解出 0 个月", HabitArchive.decode("").size(), 0);
        eq("null 解出 0 个月", HabitArchive.decode(null).size(), 0);
        eq("空归档编码", HabitArchive.encode(new ArrayList<HabitArchive.Month>()).length(), 0);

        // ---------------------------------------------------------- 习惯名编解码
        // 名字的长度是「冒号后几位」，解码必须按这个长度精确取：
        // 名字里可以有空格、可以以空格开头、可以有冒号、可以有中文和 emoji。
        String[] tricky = {" 早睡", "有 空格 的名字", "早:睡", "  ", "中文名", "🌙 早睡"};
        for (String nm : tricky) {
            ArrayList<HabitArchive.Month> t1 = new ArrayList<>();
            t1.add(monthOf(nm));
            ArrayList<HabitArchive.Month> td = HabitArchive.decode(HabitArchive.encode(t1));
            eqs("习惯名原样编解码：" + nm, td.get(0).entries.get(0).name, nm);
            eqs("名字后面的位串没串位：" + nm, td.get(0).entries.get(0).bits, "1010000000000000000000000000000");
            eqs("名字后面的时刻串没串位：" + nm, td.get(0).entries.get(0).mins, HabitTime.encodeArr(new int[32]));
        }

        // 名字以空格开头时，后面的习惯也不能跟着串位
        HabitArchive.Month two = new HabitArchive.Month();
        two.ym = 202611;
        HabitArchive.Entry t1e = new HabitArchive.Entry();
        t1e.id = "h1"; t1e.name = " 早睡";
        t1e.bits = "1010000000000000000000000000000";
        t1e.mins = HabitTime.encodeArr(new int[32]);
        two.entries.add(t1e);
        HabitArchive.Entry t2e = new HabitArchive.Entry();
        t2e.id = "h2"; t2e.name = "跑步";
        t2e.bits = "0100000000000000000000000000000";
        t2e.mins = HabitTime.encodeArr(new int[32]);
        two.entries.add(t2e);
        ArrayList<HabitArchive.Month> t3 = new ArrayList<>();
        t3.add(two);
        ArrayList<HabitArchive.Month> t3d = HabitArchive.decode(HabitArchive.encode(t3));
        eq("两条习惯都解出来", t3d.get(0).entries.size(), 2);
        eqs("第一条名字原样", t3d.get(0).entries.get(0).name, " 早睡");
        eqs("第一条 id", t3d.get(0).entries.get(0).id, "h1");
        eqs("第一条位串", t3d.get(0).entries.get(0).bits, "1010000000000000000000000000000");
        eqs("第二条 id 没串位", t3d.get(0).entries.get(1).id, "h2");
        eqs("第二条名字", t3d.get(0).entries.get(1).name, "跑步");
        eqs("第二条位串没串位", t3d.get(0).entries.get(1).bits, "0100000000000000000000000000000");

        // ---------------------------------------------------------- 合并
        ArrayList<HabitArchive.Month> arch = new ArrayList<>();
        HabitArchive.merge(arch, m);
        eq("合并后 1 个月", arch.size(), 1);
        HabitArchive.Month again = new HabitArchive.Month();
        again.ym = 202611;
        HabitArchive.Entry e3 = new HabitArchive.Entry();
        e3.id = "h1";
        e3.name = "早睡";
        e3.bits = "0000000000000000000000000000001";   // 第 31 天
        e3.mins = HabitTime.encodeArr(new int[32]);
        again.entries.add(e3);
        HabitArchive.merge(arch, again);
        eq("同月再合并不新增月份", arch.size(), 1);
        eqs("位串取并集", arch.get(0).entries.get(0).bits,
                "1010000000000000000000000000001");
        ok("并集后第 31 天有标记", arch.get(0).entries.get(0).marked(31));
        ok("并集后第 1 天仍有标记", arch.get(0).entries.get(0).marked(1));

        // 另一个月 → 新增
        HabitArchive.Month other = new HabitArchive.Month();
        other.ym = 202612;
        other.entries.add(e3);
        HabitArchive.merge(arch, other);
        eq("不同月新增一条", arch.size(), 2);

        // ---------------------------------------------------------- 裁剪
        ArrayList<HabitArchive.Month> many = new ArrayList<>();
        for (int i = 0; i < HabitArchive.MAX_MONTHS + 5; i++) {
            HabitArchive.Month x = new HabitArchive.Month();
            x.ym = 202601 + i;                 // 递增的 yyyyMM（跨年也不影响排序）
            many.add(x);
        }
        HabitArchive.trim(many);
        eq("裁剪后保留上限", many.size(), HabitArchive.MAX_MONTHS);
        ok("裁剪后最新月还在", many.get(0).ym > many.get(many.size() - 1).ym);

        // ---------------------------------------------------------- 分组：打卡记录
        Calendar today = cal(2026, 11, 8);        // 2026-11-08 是周日
        ArrayList<HabitArchive.Month> data = new ArrayList<>();
        // 上月：习惯 h1 在 10-01 与 10-05 打卡
        HabitArchive.Month oct = new HabitArchive.Month();
        oct.ym = 202610;
        HabitArchive.Entry o1 = new HabitArchive.Entry();
        o1.id = "h1"; o1.name = "早睡";
        o1.bits = "1000100000000000000000000000000";
        o1.mins = HabitTime.encodeArr(new int[32]);
        oct.entries.add(o1);
        data.add(oct);
        // 本月：h1 在 11-03 与 11-08，h2 在 11-08
        HabitArchive.Month nov = new HabitArchive.Month();
        nov.ym = 202611;
        HabitArchive.Entry n1 = new HabitArchive.Entry();
        n1.id = "h1"; n1.name = "早睡";
        n1.bits = "0010000100000000000000000000000";
        n1.mins = HabitTime.encodeArr(new int[32]);
        nov.entries.add(n1);
        HabitArchive.Entry n2 = new HabitArchive.Entry();
        n2.id = "h2"; n2.name = "跑步";
        n2.bits = "0000000100000000000000000000000";
        n2.mins = HabitTime.encodeArr(new int[32]);
        nov.entries.add(n2);
        data.add(nov);

        Map<String, String> names = new HashMap<>();
        names.put("h1", "早睡");
        names.put("h2", "跑步");

        ArrayList<HabitRec.MonthGrp> all = HabitRec.build(data, HabitScope.ALL, today, names);
        eq("全部范围两个月份", all.size(), 2);
        eq("月份倒序：新的在前", all.get(0).ym, 202611);
        eq("当月两个习惯分组", all.get(0).groups.size(), 2);
        eq("当月共 3 条记录", all.get(0).total(), 3);
        eq("上月一个习惯分组", all.get(1).groups.size(), 1);
        eq("上月共 2 条记录", all.get(1).total(), 2);

        // 本周：11-08 是周日，本周是 11-02(周一) ~ 11-08
        ArrayList<HabitRec.MonthGrp> wk = HabitRec.build(data, HabitScope.WEEK, today, names);
        eq("本周只涉及本月", wk.size(), 1);
        eq("本周 3 条（11-03、11-08 ×2）", wk.get(0).total(), 3);

        // 本月
        ArrayList<HabitRec.MonthGrp> mo = HabitRec.build(data, HabitScope.MONTH, today, names);
        eq("本月只 1 个月", mo.size(), 1);
        eq("本月 3 条", mo.get(0).total(), 3);

        // 上月
        ArrayList<HabitRec.MonthGrp> pv = HabitRec.build(data, HabitScope.PREV, today, names);
        eq("上月 1 个月", pv.size(), 1);
        eq("上月 2 条", pv.get(0).total(), 2);

        // 明细日期倒序
        eq("组内首条是最近的日子", mo.get(0).groups.get(0).rows.get(0).day, 8);
        eq("组内第二条是较早的日子", mo.get(0).groups.get(0).rows.get(1).day, 3);

        // 改名后历史跟新名字
        Map<String, String> renamed = new HashMap<>();
        renamed.put("h1", "早睡（改）");
        ArrayList<HabitRec.MonthGrp> rn = HabitRec.build(data, HabitScope.ALL, today, renamed);
        eqs("改名后历史用新名字", rn.get(0).groups.get(0).habitName, "早睡（改）");

        // ---------------------------------------------------------- 平铺（月 → 明细）
        // 记录页月内平铺：同一天的两个习惯同处一段，不再各自起小标题
        ArrayList<HabitRec.Flat> flat = HabitRec.flatten(all, "");
        eq("平铺后月份数不变", flat.size(), 2);
        eq("当月平铺 3 条", flat.get(0).rows.size(), 3);
        eq("平铺首条是最新的 11-08", flat.get(0).rows.get(0).day, 8);
        eq("第三条是 11-03", flat.get(0).rows.get(2).day, 3);
        // 跨习惯按天混排：同一天的两条要挨着，且分属两个习惯
        boolean twoHabitsSameDay = flat.get(0).rows.get(0).day == flat.get(0).rows.get(1).day
                && !flat.get(0).rows.get(0).habitId.equals(flat.get(0).rows.get(1).habitId);
        ok("同一天两个习惯相邻混排", twoHabitsSameDay);
        eq("上月平铺 2 条", flat.get(1).rows.size(), 2);

        // 只看一个习惯
        ArrayList<HabitRec.Flat> onlyH2 = HabitRec.flatten(all, "h2");
        eq("筛 h2 后只剩有数据的月份", onlyH2.size(), 1);
        eq("筛 h2 后 1 条", onlyH2.get(0).rows.size(), 1);
        eqs("筛出来的确实是 h2", onlyH2.get(0).rows.get(0).habitId, "h2");
        eq("筛不存在的习惯 → 空", HabitRec.flatten(all, "h9").size(), 0);
        eq("null 输入 → 空", HabitRec.flatten(null, "").size(), 0);
        eq("空月份列表 → 空", HabitRec.flatten(new ArrayList<HabitRec.MonthGrp>(), "").size(), 0);

        // ---------------------------------------------------------- 分页
        // 两级分组：月 → 习惯
        java.util.Map<Integer, String> titles = RecGroup.titles();
        titles.put(-1, "2026 年 10 月");
        titles.put(0, "早睡");
        titles.put(1, "跑步");
        ArrayList<RecGroup.Row> rows = new ArrayList<>();
        rows.add(RecGroup.Row.item(new int[]{-1, 0}, "10-01", "23:00", 0));
        rows.add(RecGroup.Row.item(new int[]{-1, 0}, "10-05", "23:10", 1));
        rows.add(RecGroup.Row.item(new int[]{-1, 1}, "10-08", "07:30", 2));
        rows.add(RecGroup.Row.item(new int[]{-1, 1}, "10-09", "07:40", 3));

        ArrayList<RecGroup.Page> pages = RecGroup.paginate(rows, titles, 3);
        int total = 0, itemsSeen = 0;
        for (RecGroup.Page p : pages) {
            total += p.lines.size();
            for (RecGroup.Row r : p.lines) {
                if (r.kind == RecGroup.ITEM) {
                    itemsSeen++;
                    ok("明细有上下文分组", r.chain != null && r.chain.length == 2);
                }
            }
        }
        eq("分页后明细条数不变", itemsSeen, 4);
        // 标题按页重复（每页都要能看出明细属于谁），所以总行数 ≥ 明细 + 标题数
        ok("分页后总行数不少于明细 + 标题", total >= 4 + 2);
        eq("第一页第一行是月标题", pages.get(0).lines.get(0).kind, RecGroup.GROUP);
        eqs("第一页第一行是月份标题", pages.get(0).lines.get(0).title, "2026 年 10 月");
        eqs("第二行是习惯标题", pages.get(0).lines.get(1).title, "早睡");
        // 每一页的第一行都必须是标题行，不能是孤立的明细
        for (int pi = 0; pi < pages.size(); pi++) {
            RecGroup.Page pg = pages.get(pi);
            if (pg.lines.isEmpty()) continue;
            ok("第 " + (pi + 1) + " 页首行是标题", pg.lines.get(0).kind == RecGroup.GROUP);
        }
        // 跨页时月份标题必须重复，否则明细成了孤儿
        if (pages.size() > 1) {
            boolean hasMonth = false, hasHabit = false;
            for (RecGroup.Row r : pages.get(1).lines) {
                if (r.kind == RecGroup.GROUP && r.gid == -1) hasMonth = true;
                if (r.kind == RecGroup.GROUP && r.gid >= 0) hasHabit = true;
            }
            ok("跨页时重复了月份标题", hasMonth);
            ok("跨页时重复了习惯标题", hasHabit);
        } else {
            ok("跨页时重复标题（单页，跳过）", true);
        }

        // 每页一行：不能死循环也不能丢行
        ArrayList<RecGroup.Page> p1 = RecGroup.paginate(rows, titles, 1);
        int t1 = 0;
        for (RecGroup.Page p : p1) t1 += p.lines.size();
        eq("每页 1 行时明细不丢", t1, 4);
        ok("每页 1 行时页数合理", p1.size() >= 4);

        // 每页两行：标题 + 一条明细
        ArrayList<RecGroup.Page> p2 = RecGroup.paginate(rows, titles, 2);
        int t2 = 0;
        for (RecGroup.Page p : p2) t2 += p.lines.size();
        ok("每页 2 行时不丢明细", t2 >= 4);

        // 空输入
        ArrayList<RecGroup.Page> pe = RecGroup.paginate(new ArrayList<RecGroup.Row>(), titles, 5);
        eq("空输入产出 1 个空页", pe.size(), 1);
        eq("空页 0 行", pe.get(0).lines.size(), 0);

        // ---------------------------------------------------------- 日历工具
        eq("上月键", HabitRec.prevYm(202611), 202610);
        eq("跨年上月键", HabitRec.prevYm(202601), 202512);
        eq("2026 年 2 月天数", HabitRec.daysInMonth(202602), 28);
        eq("2024 年 2 月天数（闰）", HabitRec.daysInMonth(202402), 29);
        eq("11 月天数", HabitRec.daysInMonth(202611), 30);

        // ---------------------------------------------------------- 记录页纵向预算
        float u = 1.7290323f;               // 1448×1072 横屏的 u = min(W/420, H/620)
        float tabH = 102f;
        int r13 = RecLayout.habitRowsPerPage(u, 1.3f / 1.3f, tabH, 1072f);
        int r31 = RecLayout.habitRowsPerPage(u, 3.1f / 1.3f, tabH, 1072f);
        ok("最小档每页行数够用", r13 >= 10);
        ok("最大档每页仍能放几行", r31 >= 4);
        ok("行数随字号递减", r13 > r31);
        ok("行数不会变成 0", RecLayout.rowsPerPage(900, 100, 24, 8) >= 1);
        ok("行高为 0 时兜底 1 行", RecLayout.rowsPerPage(900, 100, 0, 8) == 1);

        System.out.println("  " + (pass == 0 ? "" : "") + "pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static Calendar cal(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }

    /** 只含一条习惯的月份，用来做名字编解码的往返测试。 */
    private static HabitArchive.Month monthOf(String name) {
        HabitArchive.Month m = new HabitArchive.Month();
        m.ym = 202611;
        HabitArchive.Entry e = new HabitArchive.Entry();
        e.id = "h1";
        e.name = name;
        e.bits = "1010000000000000000000000000000";
        e.mins = HabitTime.encodeArr(new int[32]);
        m.entries.add(e);
        return m;
    }
}
