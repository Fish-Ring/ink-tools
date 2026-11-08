/**
 * 电量累加算法的单测：覆盖「离开」「充电发生在 App 未运行期间」「正常放电」三类路径。
 * 零依赖，可直接编译运行（也由 tests/run.sh 与 build.sh 自动执行）：
 *   javac -d /tmp/bt src/ink/tools/BattMath.java tests/BatteryTest.java
 *   java  -cp /tmp/bt ink.tools.BatteryTest
 */
package ink.tools;

public class BatteryTest {
    static int pass = 0, fail = 0;

    static void eq(String what, long got, long exp) {
        if (got == exp) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void eq(String what, double got, double exp) {
        if (Math.abs(got - exp) < 1e-9) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void ok(String what, boolean v) {
        if (v) { pass++; System.out.println("OK   " + what); }
        else { fail++; System.out.println("FAIL " + what); }
    }

    /** 60 秒一次的采样序列：把 pct 依次喂进去。 */
    static void feed(BattMath.Acc a, long t0, int... pcts) {
        long t = t0;
        for (int i = 0; i < pcts.length; i++) {
            BattMath.today(a, t, pcts[i]);
            BattMath.charge(a, t, pcts[i]);
            t += 60000L;
        }
    }

    public static void main(String[] args) {
        long t0 = 1_700_000_000_000L;

        // ---- 正常放电：3 次采样，每分钟掉 1%
        BattMath.Acc a = new BattMath.Acc();
        feed(a, t0, 100, 99, 98);
        eq("放电 drop", a.drop, 2);
        eq("放电 ms", a.ms, 120000L);
        eq("放电 from", a.from, 100);
        eq("放电 pct", a.pct, 98);
        eq("放电 charges 标记", a.charged ? 1 : 0, 0);

        // ---- 离开 2 小时后回来：不累加时长与掉电，只挪基点
        long before = a.ms;
        int dropBefore = a.drop;
        BattMath.today(a, t0 + 2 * 3600_000L, 80);
        eq("离开后 ms 不变", a.ms, before);
        eq("离开后 drop 不变", a.drop, dropBefore);
        eq("离开后 pct 跟到当前", a.pct, 80);
        ok("离开后 pct 上涨不算充电（80 > 98 不成立）", !a.charged);

        // ---- 充电发生在 App 未运行期间：电量从 30 跳到 100
        // 两个口径是两条独立记录，必须分别喂
        BattMath.Acc td = new BattMath.Acc();      // 今日
        BattMath.Acc tb = new BattMath.Acc();      // 本次充电后
        BattMath.today(td, t0, 30);
        BattMath.charge(tb, t0, 30);
        BattMath.today(td, t0 + 60000L, 29);
        BattMath.charge(tb, t0 + 60000L, 29);
        long t1 = t0 + 3 * 3600_000L;              // 三小时后，App 重新打开
        BattMath.today(td, t1, 100);
        eq("跨充电：今日记到充电", td.charged ? 1 : 0, 1);
        eq("跨充电：今日起点电量挪到充电后", td.from, 100);
        eq("跨充电：今日掉电保留（充电前的 1%）", td.drop, 1);
        BattMath.charge(tb, t1, 100);
        eq("跨充电：本轮重新起算 drop", tb.drop, 0);
        eq("跨充电：本轮起点电量", tb.from, 100);
        eq("跨充电：本轮起点时刻", tb.start, t1);

        // ---- 充电中（电量上升多次）只计一次
        BattMath.Acc d = new BattMath.Acc();
        BattMath.today(d, t0, 50);
        BattMath.today(d, t0 + 60000L, 60);
        BattMath.today(d, t0 + 120000L, 70);
        eq("充电中连续上升不重复计数", d.charged ? 1 : 0, 1);
        eq("充电中不计掉电", d.drop, 0);

        // ---- 无效采样
        BattMath.Acc e = new BattMath.Acc();
        ok("pct=-1 直接忽略", !BattMath.today(e, t0, -1) && !e.seeded());
        BattMath.today(e, t0, 50);
        ok("未 seed 时首采样直接种入", e.seeded() && e.from == 50);

        // ---- 折算率
        eq("30 分钟掉 5% -> 10%/h", BattMath.perHour(5, 30 * 60000L), 10.0);
        eq("样本不足 5 分钟不给", BattMath.perHour(2, 4 * 60000L), -1.0);

        // ---- 近 7 天跨月
        ok("本月 1 号与上月 31 号相差 1 天", BattMath.dayNum(20261101L) - BattMath.dayNum(20261031L) == 1);
        ok("闰年 2 月 29 到 3 月 1 日相差 1 天",
                BattMath.dayNum(20240301L) - BattMath.dayNum(20240229L) == 1);
        ok("7 天窗口含今天", BattMath.withinDays(20261108L, 20261108L, 7));
        ok("7 天窗口含 6 天前", BattMath.withinDays(20261102L, 20261108L, 7));
        ok("7 天窗口不含 7 天前", !BattMath.withinDays(20261101L, 20261108L, 7));
        ok("未来日期不算", !BattMath.withinDays(20261109L, 20261108L, 7));

        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
