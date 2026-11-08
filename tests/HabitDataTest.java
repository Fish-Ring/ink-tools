package ink.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 「打卡历史按月归档」数据层的单测。
 *
 * 这两段逻辑原本埋在 Store.java 里，而 Store 依赖 android.*、桌面上测不了，
 * 所以抽成了纯逻辑类（HabitArchive.view / HabitIds），这里直接测它们：
 * - 同月的归档与活着的当前月怎么合（丢打卡数据的高危点）；
 * - 习惯 id 怎么发（撞号会让打卡历史串到错的习惯上）。
 */
public class HabitDataTest {

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
        // ------------------------------------------------ Bug 1：同月的归档不能被丢掉
        //
        // 复现路径：用户把系统时间往回拨。11 月 → 10 月 → 11 月，
        // 同一个 11 月被切成两段：归档里存着第一段，活着的位图是第二段。
        ArrayList<HabitArchive.Month> arch = new ArrayList<>();
        arch.add(month(202611, entry("h1", "早睡", "0010000000000000000000000000000")));   // 第一段 11 月：11-03
        arch.add(month(202610, entry("h1", "早睡", "1000000000000000000000000000000")));   // 上月：10-01
        HabitArchive.Month live = month(202611, entry("h1", "早睡", "0000100000000000000000000000000"));  // 第二段 11 月：11-05

        ArrayList<HabitArchive.Month> view = HabitArchive.view(arch, live);

        eq("视图里 202611 只出现一次（不当成两个月）", countYm(view, 202611), 1);
        eq("视图共 2 个月", view.size(), 2);
        eq("月份倒序：11 月在前", view.get(0).ym, 202611);
        eq("11 月并成一条习惯记录", view.get(0).entries.size(), 1);
        ok("第一段的 11-03 还在", view.get(0).entries.get(0).marked(3));
        ok("第二段的 11-05 还在", view.get(0).entries.get(0).marked(5));
        ok("没打卡的日子仍然是空的", !view.get(0).entries.get(0).marked(1));
        ok("没打卡的日子仍然是空的（月末）", !view.get(0).entries.get(0).marked(31));
        eq("11 月共 2 天打卡", HabitArchive.totalDays(view.get(0)), 2);
        eq("10 月共 1 天打卡", HabitArchive.totalDays(view.get(1)), 1);

        // 存档本体不能被改：活月的数据不能提前写进存档（否则下次跨月再归档会重复）
        eq("存档里的 202611 仍是 1 天", HabitArchive.totalDays(arch.get(0)), 1);
        ok("存档里的 202611 没有混进第二段", !arch.get(0).entries.get(0).marked(5));
        eq("存档条数没变", arch.size(), 2);

        // 同月新增的习惯（10 月新建的）要并进同一个月，而不是另起一个月
        ArrayList<HabitArchive.Month> arch2 = new ArrayList<>();
        arch2.add(month(202611, entry("h1", "早睡", "0010000000000000000000000000000")));
        HabitArchive.Month live2 = month(202611, entry("h2", "跑步", "0000100000000000000000000000000"));
        ArrayList<HabitArchive.Month> view2 = HabitArchive.view(arch2, live2);
        eq("同月新增习惯仍是一个月", view2.size(), 1);
        eq("同月两条习惯记录", view2.get(0).entries.size(), 2);

        // 四种 scope 仍然正确
        Map<String, String> names = new HashMap<>();
        names.put("h1", "早睡");
        Calendar today = cal(2026, 11, 8);          // 周日 → 本周是 11-02 ~ 11-08

        ArrayList<HabitRec.MonthGrp> all = HabitRec.build(view, HabitScope.ALL, today, names);
        eq("全部范围 2 个月", all.size(), 2);
        eq("全部范围 11 月 2 条（两段都在）", all.get(0).total(), 2);
        eq("全部范围 11 月只有一个习惯分组", all.get(0).groups.size(), 1);
        eq("全部范围 10 月 1 条", all.get(1).total(), 1);

        ArrayList<HabitRec.MonthGrp> mo = HabitRec.build(view, HabitScope.MONTH, today, names);
        eq("本月 1 个月", mo.size(), 1);
        eq("本月 2 条（两段都在）", mo.get(0).total(), 2);

        ArrayList<HabitRec.MonthGrp> pv = HabitRec.build(view, HabitScope.PREV, today, names);
        eq("上月 1 个月", pv.size(), 1);
        eq("上月 1 条", pv.get(0).total(), 1);

        ArrayList<HabitRec.MonthGrp> wk = HabitRec.build(view, HabitScope.WEEK, today, names);
        eq("本周 1 个月", wk.size(), 1);
        eq("本周 2 条（11-03、11-05 都在本周）", wk.get(0).total(), 2);

        // ------------------------------------------------ Bug 2：发号不能撞车
        //
        // 设备 nextHabitId=1；备份里习惯 A 的 id 是 h1，习惯 B 是老备份、没有 id 字段。
        // 修复前 B 直接拿设备计数器 → 也拿到 h1 → 两个习惯同 id → 打卡历史串掉。
        ArrayList<String> fileIds = new ArrayList<>();
        fileIds.add("h1");
        fileIds.add("");
        ArrayList<String> ids = HabitIds.assign(fileIds, 1, 0);
        eqs("备份里已有的号原样保留", ids.get(0), "h1");
        ok("新发的号不等于已占用的 h1", !ids.get(1).equals("h1"));
        eqs("新发的号是 h2", ids.get(1), "h2");

        // 归档里还有别的号（老习惯已删除，但历史还在）时，新号也要避开
        ArrayList<HabitArchive.Month> arch3 = new ArrayList<>();
        arch3.add(month(202611, entry("h7", "老习惯", "0000000000000000000000000000000")));
        int reserved = HabitIds.maxNumOfMonths(arch3);
        eq("归档里的最大编号", reserved, 7);
        ArrayList<String> ids2 = HabitIds.assign(fileIds, 1, reserved);
        ok("新号避开归档里的 h7", !ids2.get(1).equals("h7"));
        eqs("新号是 h8", ids2.get(1), "h8");

        // 备份里自己就有重复 id：后一个也得换号
        ArrayList<String> dup = new ArrayList<>();
        dup.add("h1");
        dup.add("h1");
        ArrayList<String> ids3 = HabitIds.assign(dup, 1, 0);
        eqs("重复 id 的第一个保留", ids3.get(0), "h1");
        ok("重复 id 的第二个换号", !ids3.get(1).equals("h1"));

        // 设备计数器比备份里的号大时，也不能发到已被占用的号上
        ArrayList<String> ids5 = HabitIds.assign(fileIds, 5, 0);
        ok("设备计数器更大时也不撞车", !ids5.get(1).equals("h1"));
        eqs("设备计数器更大时发 h6", ids5.get(1), "h6");

        // 全员缺 id 的老备份：沿用设备计数器，不跳号
        ArrayList<String> none = new ArrayList<>();
        none.add("");
        none.add("");
        ArrayList<String> ids4 = HabitIds.assign(none, 1, 0);
        eqs("老备份第一个发 h1", ids4.get(0), "h1");
        eqs("老备份第二个发 h2", ids4.get(1), "h2");

        // 畸形 id 一律忽略（不参与占号，也不能被当成编号）
        eq("畸形 id 忽略：h", HabitIds.num("h"), 0);
        eq("畸形 id 忽略：h1x", HabitIds.num("h1x"), 0);
        eq("畸形 id 忽略：abc", HabitIds.num("abc"), 0);
        eq("畸形 id 忽略：null", HabitIds.num(null), 0);
        eq("畸形 id 忽略：h0", HabitIds.num("h0"), 0);
        eq("畸形 id 忽略：空串", HabitIds.num(""), 0);
        eq("正常 id 解析", HabitIds.num("h12"), 12);

        // 畸形 id 不影响占号：文件里只有 "abc" 时，从设备计数器接着发
        ArrayList<String> weird = new ArrayList<>();
        weird.add("abc");
        weird.add("");
        ArrayList<String> ids6 = HabitIds.assign(weird, 1, 0);
        eqs("畸形 id 原样保留", ids6.get(0), "abc");
        eqs("畸形 id 不占号", ids6.get(1), "h1");

        System.out.println("  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 小工具

    private static HabitArchive.Entry entry(String id, String name, String bits) {
        HabitArchive.Entry e = new HabitArchive.Entry();
        e.id = id;
        e.name = name;
        e.bits = bits;
        e.mins = HabitTime.encodeArr(new int[32]);
        return e;
    }

    private static HabitArchive.Month month(int ym, HabitArchive.Entry... es) {
        HabitArchive.Month m = new HabitArchive.Month();
        m.ym = ym;
        for (HabitArchive.Entry e : es) m.entries.add(e);
        return m;
    }

    private static int countYm(ArrayList<HabitArchive.Month> months, int ym) {
        int n = 0;
        for (HabitArchive.Month m : months) if (m.ym == ym) n++;
        return n;
    }

    private static Calendar cal(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }
}
