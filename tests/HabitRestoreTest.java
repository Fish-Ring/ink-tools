package ink.tools;

import java.util.ArrayList;

/**
 * 「打卡历史按月归档」的数据层单测（主 bug + 附带 bug A/B）。
 *
 * 主 bug：系统时间往回拨又拨回来（11 月 → 10 月 → 11 月）时，
 * 同一个 11 月被切成两段——第一段进了归档、活位图被清空；
 * 拨回 11 月后归档里留着当月数据、活位图却是空的，
 * 打卡页说「本月 0 次」、记录页说当月有记录，两页自相矛盾。
 *
 * 修法：当前月永远是活的，归档只留「已经结束的月」。
 * 进到某个月时把归档里该月的数据并回活位图（并集，不覆盖），并把归档里该月那条移除。
 *
 * 附带 bug A：Store.load() 给缺 id 的习惯发号时没把归档里已占用的号算进来 → 撞号。
 * 附带 bug B：HabitArchive.mergeMins 把 UNKNOWN(-1) 当「非零」保留 → 真实时刻被丢掉。
 *
 * 这些逻辑都在纯逻辑类里（HabitArchive / HabitIds），桌面 JVM 直接可测。
 */
public class HabitRestoreTest {

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
        // ================================================== 主 bug：归档 ↔ 活位图对账
        //
        // 场景 1：归档里有当月数据，活位图是空的
        //   → 恢复后活位图有那些天，归档里当月那条被移除
        ArrayList<HabitArchive.Month> arch1 = new ArrayList<>();
        arch1.add(month(202611, entry("h1", "早睡", new int[]{6, 7}, null)));
        ArrayList<HabitArchive.Slot> slots1 = new ArrayList<>();
        slots1.add(slot("h1"));                       // 活位图空
        ok("场景1：归档里有当月 → 对账生效", HabitArchive.reconcileMonth(arch1, 202611, slots1));
        ok("场景1：11-06 恢复进活位图", slots1.get(0).days[6]);
        ok("场景1：11-07 恢复进活位图", slots1.get(0).days[7]);
        ok("场景1：没打卡的日子仍然是空的", !slots1.get(0).days[8]);
        ok("场景1：月末没打卡", !slots1.get(0).days[31]);
        eq("场景1：归档里当月条目被移除", countYm(arch1, 202611), 0);
        eq("场景1：归档里没有别的月份被误删", arch1.size(), 0);

        // 场景 2：归档里有当月数据，活位图已有别的打卡
        //   → 两者都在（并集，不覆盖用户新打的卡）
        ArrayList<HabitArchive.Month> arch2 = new ArrayList<>();
        arch2.add(month(202611, entry("h1", "早睡", new int[]{6, 7}, null)));
        ArrayList<HabitArchive.Slot> slots2 = new ArrayList<>();
        slots2.add(slot("h1", 8));                    // 活位图已有 11-08
        ok("场景2：对账生效", HabitArchive.reconcileMonth(arch2, 202611, slots2));
        ok("场景2：归档的 11-06 在", slots2.get(0).days[6]);
        ok("场景2：归档的 11-07 在", slots2.get(0).days[7]);
        ok("场景2：活位图原有的 11-08 没被覆盖", slots2.get(0).days[8]);
        ok("场景2：没打卡的日子仍然空", !slots2.get(0).days[9]);
        eq("场景2：归档里当月条目被移除", countYm(arch2, 202611), 0);

        // 场景 3：归档里有当月数据，但那个习惯 id 在活习惯里已经不存在（习惯被删了）
        //   → 恢复时跳过，活位图不变，归档条目仍被移除（它属于当前月）
        ArrayList<HabitArchive.Month> arch3 = new ArrayList<>();
        arch3.add(month(202611, entry("h9", "已删除的习惯", new int[]{6, 7}, null)));
        ArrayList<HabitArchive.Slot> slots3 = new ArrayList<>();
        slots3.add(slot("h1", 8));
        ok("场景3：对账照样生效", HabitArchive.reconcileMonth(arch3, 202611, slots3));
        ok("场景3：陌生 id 没有塞进活位图", !slots3.get(0).days[6]);
        ok("场景3：活位图原有数据不变", slots3.get(0).days[8]);
        eq("场景3：槽位数量没变（没凭空多出习惯）", slots3.size(), 1);
        eq("场景3：归档里当月条目仍被移除", countYm(arch3, 202611), 0);

        // 场景 4：归档里没有当月数据 → 什么都不变（不要误删）
        ArrayList<HabitArchive.Month> arch4 = new ArrayList<>();
        arch4.add(month(202610, entry("h1", "早睡", new int[]{1}, null)));
        ArrayList<HabitArchive.Slot> slots4 = new ArrayList<>();
        slots4.add(slot("h1", 8));
        ok("场景4：归档里没有当月 → 对账不生效", !HabitArchive.reconcileMonth(arch4, 202611, slots4));
        eq("场景4：归档月份数没变", arch4.size(), 1);
        eq("场景4：10 月还在", arch4.get(0).ym, 202610);
        ok("场景4：10 月的数据没被动", arch4.get(0).entries.get(0).marked(1));
        ok("场景4：活位图没变", slots4.get(0).days[8] && !slots4.get(0).days[1]);
        ok("场景4：空归档也不炸", !HabitArchive.reconcileMonth(new ArrayList<HabitArchive.Month>(), 202611, slots4));
        ok("场景4：空槽位也不炸", !HabitArchive.reconcileMonth(arch4, 202611, new ArrayList<HabitArchive.Slot>()));

        // 场景 5：时刻（mins）
        //   归档里某天是真实时刻、活位图某天是真实时刻 → 各自保留
        int[] real6 = new int[32]; real6[6] = HabitTime.encode(23, 0);
        int[] real8 = new int[32]; real8[8] = HabitTime.encode(7, 30);
        int[] unk6 = new int[32]; unk6[6] = HabitTime.UNKNOWN;
        ArrayList<HabitArchive.Month> arch5 = new ArrayList<>();
        arch5.add(month(202611, entry("h1", "早睡", new int[]{6}, real6)));
        ArrayList<HabitArchive.Slot> slots5 = new ArrayList<>();
        slots5.add(slotAt("h1", 8, HabitTime.encode(7, 30)));
        HabitArchive.reconcileMonth(arch5, 202611, slots5);
        eq("场景5：归档的 11-06 时刻保留", slots5.get(0).mins[6], HabitTime.encode(23, 0));
        eq("场景5：活位图的 11-08 时刻保留", slots5.get(0).mins[8], HabitTime.encode(7, 30));

        // 归档里是 UNKNOWN(-1)、活位图是真实时刻 → 合并后应是真实时刻
        ArrayList<HabitArchive.Month> arch5b = new ArrayList<>();
        arch5b.add(month(202611, entry("h1", "早睡", new int[]{6}, unk6)));
        ArrayList<HabitArchive.Slot> slots5b = new ArrayList<>();
        slots5b.add(slotAt("h1", 6, HabitTime.encode(23, 0)));
        HabitArchive.reconcileMonth(arch5b, 202611, slots5b);
        eq("场景5：UNKNOWN 让位给真实时刻", slots5b.get(0).mins[6], HabitTime.encode(23, 0));

        // 归档里是真实时刻、活位图是 UNKNOWN(-1) → 合并后应是真实时刻
        ArrayList<HabitArchive.Month> arch5c = new ArrayList<>();
        arch5c.add(month(202611, entry("h1", "早睡", new int[]{6}, real6)));
        ArrayList<HabitArchive.Slot> slots5c = new ArrayList<>();
        slots5c.add(slotAt("h1", 6, HabitTime.UNKNOWN));
        HabitArchive.reconcileMonth(arch5c, 202611, slots5c);
        eq("场景5：真实时刻盖过 UNKNOWN", slots5c.get(0).mins[6], HabitTime.encode(23, 0));

        // 两边都是 UNKNOWN → 还是 UNKNOWN（打卡标记在，时刻未知）
        ArrayList<HabitArchive.Month> arch5d = new ArrayList<>();
        arch5d.add(month(202611, entry("h1", "早睡", new int[]{6}, unk6)));
        ArrayList<HabitArchive.Slot> slots5d = new ArrayList<>();
        slots5d.add(slotAt("h1", 6, HabitTime.UNKNOWN));
        HabitArchive.reconcileMonth(arch5d, 202611, slots5d);
        eq("场景5：两边都是未知时间仍是未知", slots5d.get(0).mins[6], HabitTime.UNKNOWN);

        // 场景 6：恢复不能改动归档本体里其它月份的数据
        ArrayList<HabitArchive.Month> arch6 = new ArrayList<>();
        arch6.add(month(202610, entry("h1", "早睡", new int[]{1, 2}, null)));
        arch6.add(month(202611, entry("h1", "早睡", new int[]{6}, null)));
        arch6.add(month(202612, entry("h1", "早睡", new int[]{9}, null)));
        ArrayList<HabitArchive.Month> copy6 = HabitArchive.copy(arch6);      // 深拷贝做对照
        ArrayList<HabitArchive.Slot> slots6 = new ArrayList<>();
        slots6.add(slot("h1", 8));
        HabitArchive.reconcileMonth(arch6, 202611, slots6);
        eq("场景6：归档月份数只剩 2 个", arch6.size(), 2);
        eqs("场景6：10 月的数据没被动", findYm(arch6, 202610).entries.get(0).bits,
                copy6.get(0).entries.get(0).bits);
        eqs("场景6：12 月的数据没被动", findYm(arch6, 202612).entries.get(0).bits,
                copy6.get(2).entries.get(0).bits);
        eqs("场景6：10 月的时刻没被动", findYm(arch6, 202610).entries.get(0).mins,
                copy6.get(0).entries.get(0).mins);
        ok("场景6：10 月的打卡标记还在", findYm(arch6, 202610).entries.get(0).marked(1));
        ok("场景6：12 月的打卡标记还在", findYm(arch6, 202612).entries.get(0).marked(9));
        // copy() 仍是深拷贝：改副本不动本体
        String orig10 = findYm(arch6, 202610).entries.get(0).bits;
        copy6.get(0).entries.get(0).bits = "1111111111111111111111111111111";
        eqs("场景6：copy() 是深拷贝（改副本不动本体）",
                findYm(arch6, 202610).entries.get(0).bits, orig10);
        ok("场景6：副本确实被改过（对照有效）",
                !orig10.equals("1111111111111111111111111111111"));

        // 场景 7：端到端复现真机路径（11 月 → 10 月 → 11 月）
        //
        // 起点：11 月活位图有 11-06、11-07，归档空
        ArrayList<HabitArchive.Slot> live = new ArrayList<>();
        live.add(slot("h1", 6, 7));
        ArrayList<HabitArchive.Month> arch7 = new ArrayList<>();

        // 拨到 10 月：跨月 → 归档 11 月 → 清空活位图
        HabitArchive.merge(arch7, HabitArchive.snapshot(202611, live));
        clearSlots(live);
        eq("场景7：拨到 10 月后 11 月进了归档", countYm(arch7, 202611), 1);
        ok("场景7：拨到 10 月后活位图是空的", !live.get(0).days[6]);

        // 10 月期间在活位图打了 10-08
        live.get(0).days[8] = true;

        // 拨回 11 月：跨月 → 归档 10 月 → 清空活位图 → 对账（归档里还留着 11 月）
        HabitArchive.merge(arch7, HabitArchive.snapshot(202610, live));
        clearSlots(live);
        HabitArchive.reconcileMonth(arch7, 202611, live);

        ok("场景7：11-06 回到活位图", live.get(0).days[6]);
        ok("场景7：11-07 回到活位图", live.get(0).days[7]);
        eq("场景7：归档里不再有 11 月", countYm(arch7, 202611), 0);
        eq("场景7：归档里 10 月还在", countYm(arch7, 202610), 1);
        eq("场景7：10 月那 1 天没丢", HabitArchive.totalDays(findYm(arch7, 202610)), 1);

        // 两页不再自相矛盾：打卡页（活位图）与记录页（归档 + 活月合并）说的是同一件事
        ArrayList<HabitArchive.Month> view7 = HabitArchive.view(arch7, HabitArchive.snapshot(202611, live));
        int liveDays = 0;
        for (int d = 1; d <= 31; d++) if (live.get(0).days[d]) liveDays++;
        eq("场景7：打卡页的当月天数", liveDays, 2);
        eq("场景7：记录页的当月天数", HabitArchive.totalDays(findYm(view7, 202611)), liveDays);
        eq("场景7：记录页当月只有一份（没当成两个月）", countYm(view7, 202611), 1);

        // ================================================== 附带 bug A：发号不能撞车
        //
        // 场景：存档里 h1 有 id、h2 缺 id（老数据），设备计数器停在 1（"hi" 键缺失），
        // 归档里还留着 h7（那个习惯已删除，但历史还在，号不能复用）。
        ArrayList<String> fileIds = new ArrayList<>();
        fileIds.add("h1");
        fileIds.add("");
        ArrayList<HabitArchive.Month> archA = new ArrayList<>();
        archA.add(month(202610, entry("h7", "老习惯", new int[]{1}, null)));
        int reserved = HabitIds.maxNumOfMonths(archA);
        eq("bug A：归档里的最大编号", reserved, 7);
        ArrayList<String> ids = HabitIds.assign(fileIds, 1, reserved);
        eqs("bug A：存档里已有的号原样保留", ids.get(0), "h1");
        ok("bug A：缺 id 的不撞存档里的 h1", !ids.get(1).equals("h1"));
        ok("bug A：缺 id 的不撞归档里的 h7", !ids.get(1).equals("h7"));
        eqs("bug A：缺 id 的发到 h8", ids.get(1), "h8");

        // 没有归档时：新号也不能撞存档里已有的号（老计数器停在 1 的老存档）
        ArrayList<String> idsA2 = HabitIds.assign(fileIds, 1, 0);
        eqs("bug A：没有归档时新号是 h2", idsA2.get(1), "h2");
        ok("bug A：没有归档时也不撞 h1", !idsA2.get(1).equals("h1"));

        // 存档里 id 齐全、只是计数器丢了：id 原样保留，下一个号从它们之上发
        ArrayList<String> full = new ArrayList<>();
        full.add("h1");
        full.add("h2");
        ArrayList<String> idsA3 = HabitIds.assign(full, 1, reserved);
        eqs("bug A：id 齐全时 h1 原样保留", idsA3.get(0), "h1");
        eqs("bug A：id 齐全时 h2 原样保留", idsA3.get(1), "h2");
        eq("bug A：计数器缺失时下一个号从已占用号之上发",
                HabitIds.nextFree(1, HabitIds.maxNumOfMonths(archA)), 8);

        // ================================================== 附带 bug B：UNKNOWN 不是「有值」
        //
        // HabitTime：0 = 未打卡，-1 = 打过卡但没时间（补记），>0 = 真实时刻。
        // 合并时必须是「真实时刻 > UNKNOWN > 未打卡」，否则真实时刻会被补记盖掉。
        int[] none = new int[32];
        int[] m1 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(unk6), HabitTime.encodeArr(real6)));
        eq("bug B：真实时刻优先于 UNKNOWN", m1[6], HabitTime.encode(23, 0));
        int[] m2 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(real6), HabitTime.encodeArr(unk6)));
        eq("bug B：反向合并同样保留真实时刻", m2[6], HabitTime.encode(23, 0));
        int[] m3 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(real6), HabitTime.encodeArr(real8)));
        eq("bug B：不同天的真实时刻各自保留（1）", m3[6], HabitTime.encode(23, 0));
        eq("bug B：不同天的真实时刻各自保留（2）", m3[8], HabitTime.encode(7, 30));
        int[] m4 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(unk6), HabitTime.encodeArr(none)));
        eq("bug B：UNKNOWN 优于未打卡", m4[6], HabitTime.UNKNOWN);
        int[] m5 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(none), HabitTime.encodeArr(unk6)));
        eq("bug B：UNKNOWN 优于未打卡（反向）", m5[6], HabitTime.UNKNOWN);
        int[] m6 = HabitTime.decode(HabitArchive.mergeMins(
                HabitTime.encodeArr(none), HabitTime.encodeArr(none)));
        eq("bug B：两边未打卡还是未打卡", m6[6], HabitTime.NONE);

        eq("bug B：betterMin 真实时刻优先", HabitArchive.betterMin(HabitTime.UNKNOWN, 100), 100);
        eq("bug B：betterMin UNKNOWN 优于 0", HabitArchive.betterMin(HabitTime.UNKNOWN, HabitTime.NONE),
                HabitTime.UNKNOWN);
        eq("bug B：betterMin 0 与 0", HabitArchive.betterMin(HabitTime.NONE, HabitTime.NONE), HabitTime.NONE);
        eq("bug B：betterMin 真实时刻与真实时刻取先有的",
                HabitArchive.betterMin(HabitTime.encode(23, 0), HabitTime.encode(7, 30)),
                HabitTime.encode(23, 0));

        // merge()（归档 + 活月）也走同一套时刻语义：
        // 归档里是补记、并进来的活月是真实时刻 → 真实时刻胜出
        ArrayList<HabitArchive.Month> archB = new ArrayList<>();
        archB.add(month(202610, entry("h1", "早睡", new int[]{6}, unk6)));
        HabitArchive.merge(archB, month(202610, entry("h1", "早睡", new int[]{6}, real6)));
        eq("bug B：merge 里真实时刻不被补记盖掉",
                HabitTime.decode(archB.get(0).entries.get(0).mins)[6], HabitTime.encode(23, 0));

        // 反向：归档里是真实时刻、并进来的活月是补记 → 真实时刻仍然胜出
        ArrayList<HabitArchive.Month> archB2 = new ArrayList<>();
        archB2.add(month(202610, entry("h1", "早睡", new int[]{6}, real6)));
        HabitArchive.merge(archB2, month(202610, entry("h1", "早睡", new int[]{6}, unk6)));
        eq("bug B：merge 反向也保留真实时刻",
                HabitTime.decode(archB2.get(0).entries.get(0).mins)[6], HabitTime.encode(23, 0));

        System.out.println("  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 小工具

    /** 活位图槽位：列出的日子算打卡，时刻未知（补记）。 */
    private static HabitArchive.Slot slot(String id, int... days) {
        HabitArchive.Slot s = new HabitArchive.Slot();
        s.id = id;
        for (int d : days) {
            if (d < 1 || d > 31) continue;
            s.days[d] = true;
            s.mins[d] = HabitTime.UNKNOWN;
        }
        return s;
    }

    /** 活位图槽位：某一天打卡，时刻是给定的值。 */
    private static HabitArchive.Slot slotAt(String id, int day, int min) {
        HabitArchive.Slot s = new HabitArchive.Slot();
        s.id = id;
        s.days[day] = true;
        s.mins[day] = min;
        return s;
    }

    /** 归档条目：marks 里列出的日子算打卡；mins 为 null 时用 UNKNOWN。 */
    private static HabitArchive.Entry entry(String id, String name, int[] marks, int[] mins) {
        HabitArchive.Entry e = new HabitArchive.Entry();
        e.id = id;
        e.name = name;
        boolean[] d = new boolean[32];
        for (int x : marks) {
            if (x >= 1 && x <= 31) d[x] = true;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 31; i++) b.append(d[i] ? '1' : '0');
        e.bits = b.toString();
        if (mins == null) {
            mins = new int[32];
            for (int x : marks) {
                if (x >= 1 && x <= 31) mins[x] = HabitTime.UNKNOWN;
            }
        }
        e.mins = HabitTime.encodeArr(mins);
        return e;
    }

    private static HabitArchive.Month month(int ym, HabitArchive.Entry... es) {
        HabitArchive.Month m = new HabitArchive.Month();
        m.ym = ym;
        for (HabitArchive.Entry e : es) m.entries.add(e);
        return m;
    }

    /** 跨月时清空活位图（与 Store.rolloverIfNeeded 同一步）。 */
    private static void clearSlots(ArrayList<HabitArchive.Slot> slots) {
        for (HabitArchive.Slot s : slots) {
            java.util.Arrays.fill(s.days, false);
            java.util.Arrays.fill(s.mins, HabitTime.NONE);
        }
    }

    private static int countYm(ArrayList<HabitArchive.Month> months, int ym) {
        int n = 0;
        for (HabitArchive.Month m : months) if (m.ym == ym) n++;
        return n;
    }

    private static HabitArchive.Month findYm(ArrayList<HabitArchive.Month> months, int ym) {
        for (HabitArchive.Month m : months) if (m.ym == ym) return m;
        return null;
    }
}
