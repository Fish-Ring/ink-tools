/**
 * 本轮 7 处改动里所有「纯计算」部分的回归测试。
 * 不 import android.*，由 tests/run.sh 在桌面 JVM 上直接跑。
 */
package ink.tools;

import java.util.Calendar;

public class UiRoundTest {
    static int pass = 0, fail = 0;

    static void eq(String what, float got, float exp) {
        if (Math.abs(got - exp) < 0.15f) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void eq(String what, int got, int exp) {
        if (got == exp) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void ok(String what, boolean v) {
        if (v) { pass++; System.out.println("OK   " + what); }
        else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------------- ① 时钟盒居中
        // 无农历：数字独占盒子，墨迹中心必须等于盒子中心
        float[] a = ClockBox.digitArea(100, 300, false, 0, 6);
        eq("无农历可用区 top", a[0], 106);
        eq("无农历可用区 bottom", a[1], 294);
        float px = ClockBox.digitPx(a[1] - a[0], 0.82f);
        eq("数字字号", px, 154.16f);
        float base = ClockBox.baseline(a[0], a[1], px * 0.75f, px * 0.22f);
        eq("墨迹中心 = 盒子中心", ClockBox.inkCenter(base, px * 0.75f, px * 0.22f), 200);
        ok("基线落在盒子内", base > 100 && base < 300);

        // 有农历：可用区底边要让开农历墨迹顶部
        float lunarBase = 292, lunarAsc = 13, lunarInkTop = lunarBase - lunarAsc;
        float[] b = ClockBox.digitArea(100, 300, true, lunarInkTop, 6);
        eq("有农历可用区 bottom", b[1], 273);
        float px2 = ClockBox.digitPx(b[1] - b[0], 0.82f);
        float base2 = ClockBox.baseline(b[0], b[1], px2 * 0.75f, px2 * 0.22f);
        eq("有农历墨迹中心", ClockBox.inkCenter(base2, px2 * 0.75f, px2 * 0.22f),
                (b[0] + b[1]) / 2f);
        ok("数字墨迹底部不压农历", base2 + px2 * 0.22f < lunarInkTop);

        // 盒子极矮：不能出现负高度或 NaN
        float[] c = ClockBox.digitArea(100, 104, false, 0, 6);
        ok("极矮盒子不反转", c[1] >= c[0]);
        ok("极矮盒子字号仍为正", ClockBox.digitPx(c[1] - c[0], 0.82f) > 0);
        // 宽度收缩：幂等是「基线与绘制字号一致」的前提
        float wide = ClockBox.fitPx(200, 1200, 600);
        eq("过宽按 0.5 收缩", wide, 100);
        eq("二次收缩幂等", ClockBox.fitPx(wide, 600, 600), wide);
        eq("本来放得下就不缩", ClockBox.fitPx(200, 500, 600), 200);
        ok("非法宽度不产生非正字号", ClockBox.fitPx(200, 0, -1) > 0);

        // ---------------------------------------------------------------- ② 日期 / 置顶基线对齐
        float ref = CalHead.alignBaseline(200, 40, 40);
        eq("字号相同不改变基线", ref, 200);
        float small = CalHead.alignBaseline(200, 40, 30);
        ok("小字基线上移", small < 200 && small > 195);
        // 墨迹中心：大字 200 - 0.34*40，小字 small - 0.34*30
        eq("大小字墨迹中心一致", small - 0.34f * 30f, 200 - 0.34f * 40f);

        // ---------------------------------------------------------------- ③ 时钟页两行信息
        ClockStrip.Layout l1 = ClockStrip.layout(500, 40, 700, 6, true, true);
        eq("倒数占第一行", l1.countBase, 500);
        eq("农历退到第二行", l1.metaBase, 540);
        ClockStrip.Layout l2 = ClockStrip.layout(500, 40, 530, 6, true, true);
        eq("第二行放不下", l2.metaBase, -1);
        eq("第一行仍在", l2.countBase, 500);
        ClockStrip.Layout l3 = ClockStrip.layout(500, 40, 700, 6, false, true);
        eq("没开倒数时不画倒数", l3.countBase, -1);
        eq("没开倒数时农历占第一行", l3.metaBase, 500);
        ClockStrip.Layout l4 = ClockStrip.layout(500, 40, 502, 6, true, true);
        eq("第一行自身放不下：倒数隐藏", l4.countBase, -1);
        eq("第一行自身放不下：农历隐藏", l4.metaBase, -1);
        ClockStrip.Layout l5 = ClockStrip.layout(500, 40, 700, 6, false, false);
        eq("两行都不要：倒数 -1", l5.countBase, -1);
        eq("两行都不要：农历 -1", l5.metaBase, -1);

        // ---------------------------------------------------------------- ⑤ 含充电总时长
        BattMath.Acc acc = new BattMath.Acc();
        long t = 1000000L;
        ok("首次只对齐基点", BattMath.gross(acc, t));
        eq("首次不计时", (float) acc.msAll, 0);
        ok("正常间隔累计", BattMath.gross(acc, t + 60000L));
        eq("累计一分钟", (float) acc.msAll, 60000f);
        eq("未充电口径没被动过", (float) acc.ms, 0);
        eq("掉电没被动过", acc.drop, 0);
        ok("离开只滑基点", !BattMath.gross(acc, t + 60000L + BattMath.GAP_MS + 1));
        eq("离开不计时", (float) acc.msAll, 60000f);
        ok("离开后恢复正常", BattMath.gross(acc, t + 60000L + BattMath.GAP_MS + 1 + 60000L));
        eq("恢复后再加一分钟", (float) acc.msAll, 120000f);
        ok("时间倒流不计时", !BattMath.gross(acc, 1L));
        eq("时间倒流不改累计", (float) acc.msAll, 120000f);
        eq("时间倒流保留基点", (float) acc.lastAll,
                (float) (t + 60000L + BattMath.GAP_MS + 1 + 60000L));
        ok("倒流后下一次正常间隔仍累计",
                BattMath.gross(acc, t + 60000L + BattMath.GAP_MS + 1 + 60000L + 60000L));
        eq("倒流后累计继续增长", (float) acc.msAll, 180000f);
        // 小于 MIN_STEP 的同分重绘：不重复计时
        BattMath.Acc tiny = new BattMath.Acc();
        BattMath.gross(tiny, t);
        ok("短间隔不计时", !BattMath.gross(tiny, t + 500L));
        eq("短间隔累计不变", (float) tiny.msAll, 0f);
        // discharge 的时间倒流同样不能把基点回拨
        BattMath.Acc da = new BattMath.Acc();
        BattMath.seed(da, t, 80);
        BattMath.today(da, t + 60000L, 79);
        eq("放电累计一分钟", (float) da.ms, 60000f);
        BattMath.today(da, t - 1000L, 78);
        eq("时间回拨不计负时长", (float) da.ms, 60000f);
        eq("时间回拨保留 last", (float) da.last, (float) (t + 60000L));
        // 升级迁移：旧数据只有 ms
        BattMath.Acc old = new BattMath.Acc();
        old.ms = 3 * 3600000L;
        old.last = 12345L;
        BattMath.migrate(old);
        eq("迁移后总时长 >= 未充电", (float) old.msAll, 3 * 3600000f);
        eq("迁移后基点 = 上次采样", (float) old.lastAll, 12345f);
        // seed 不能把 lastAll 抹掉（否则充电翻转那一分钟会重复计）
        BattMath.Acc sd = new BattMath.Acc();
        sd.lastAll = 5000L;
        BattMath.seed(sd, 8000L, 80);
        eq("seed 保留 lastAll", (float) sd.lastAll, 5000f);
        eq("seed 清零总时长", (float) sd.msAll, 0);

        // ---------------------------------------------------------------- ⑥ 打卡记录范围 / 筛选
        eq("范围名 本周", HabitScope.name(HabitScope.WEEK).equals("本周") ? 1 : 0, 1);
        eq("范围名 本月", HabitScope.name(HabitScope.MONTH).equals("本月") ? 1 : 0, 1);
        eq("范围名 上月", HabitScope.name(HabitScope.PREV).equals("上月") ? 1 : 0, 1);
        eq("范围名 全部", HabitScope.name(HabitScope.ALL).equals("全部") ? 1 : 0, 1);
        eq("筛选 -1 = 全部", HabitScope.ALL_HABITS, -1);
        eq("游标越界夹回全部", HabitScope.clamp(9, 2), HabitScope.ALL_HABITS);
        eq("没有习惯时夹回全部", HabitScope.clamp(0, 0), HabitScope.ALL_HABITS);
        eq("合法游标保持", HabitScope.clamp(1, 3), 1);

        // 2026-11-08 是周日；本周（周一起）= 11-02..11-08
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2026, Calendar.NOVEMBER, 8);
        int ym = 202611;
        // 本周边界（跨月/跨年都由 DayMath 处理，这里钉住两个锚点）
        eq("本周周一 11-02", DayMath.weekStart(cal), 20261102L);
        eq("本周周日 11-08", DayMath.weekEnd(cal), 20261108L);
        // 跨年周：2026-01-01 是周四，本周从 2025-12-29（去年）开始
        Calendar ny = Calendar.getInstance();
        ny.clear();
        ny.set(2026, Calendar.JANUARY, 1);
        eq("跨年周周一 2025-12-29", DayMath.weekStart(ny), 20251229L);
        // 跨月周：2026-12-01 是周二，本周一落在 11-30
        Calendar dec = Calendar.getInstance();
        dec.clear();
        dec.set(2026, Calendar.DECEMBER, 1);
        eq("跨月周周一 11-30", DayMath.weekStart(dec), 20261130L);

        // ---------------------------------------------------------------- ⑦ 导航随字号变化
        float u = 2.335f;
        float cell = 1072f / 7f - 6f * u * 2f;
        float per = 2f;                                  // 两个汉字 / 1px 字号
        float n13 = ChromeMath.navPx(12, u, 1f, cell, per);
        float n175 = ChromeMath.navPx(12, u, 1.75f / 1.3f, cell, per);
        float n31 = ChromeMath.navPx(12, u, 3.1f / 1.3f, cell, per);
        ok("导航字号随档位递增", n13 < n175 && n175 < n31);
        eq("3.1 档封顶 1.9", n31, 12f * u * 1.9f);
        ok("导航字号小于通用正文但被放宽", n31 > 12f * u * ChromeMath.CHROME_MAX);
        float h13 = ChromeMath.navH(u, n13, 1448);
        float h31 = ChromeMath.navH(u, n31, 1448);
        ok("导航栏高度随字号递增", h31 > h13);
        ok("导航栏不吃掉超过 15% 屏高", h31 <= 1448 * 0.15f + 0.01f);
        ok("小字号仍有可点高度", h13 >= 40f * u - 0.01f);
        // 窄屏：格宽约束生效，七个标签不会被截成「台…时…番…」
        float narrow = ChromeMath.navPx(12, u, 3.1f / 1.3f, 60f, 2f);
        ok("窄屏按格宽压回", narrow <= 30f + 0.01f && narrow < 12f * u * 1.9f);
        ok("窄屏仍有下限", narrow >= 9f * u - 0.01f);
        eq("15% 屏高上限生效", ChromeMath.navH(u, 100f, 100f), 15f);
        eq("绝对下限生效", ChromeMath.navPx(12, u, 1f, 1f, 100f), 9f * u);
        ok("高度随文字单调不减",
                ChromeMath.navH(u, n31, 1448) >= ChromeMath.navH(u, n175, 1448));

        System.out.println("\npass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
