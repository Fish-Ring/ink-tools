package ink.tools;

/** BattText：时长、主数字、折算率、每月/每年折算。 */
public class BattTextTest {

    static int pass = 0, fail = 0;

    static void eq(String name, Object got, Object want) {
        if (String.valueOf(got).equals(String.valueOf(want))) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  got=" + got + " want=" + want);
        }
    }

    static void ok(String name, boolean v) {
        if (v) pass++;
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] a) {
        // ---- 时长 ----
        eq("dur 0", BattText.dur(0), "不足 1 分钟");
        eq("dur 59s", BattText.dur(59000), "不足 1 分钟");
        eq("dur 1 分钟", BattText.dur(60000), "1 分钟");
        eq("dur 38 分钟", BattText.dur(38 * 60000L), "38 分钟");
        eq("dur 60 分钟=1 小时", BattText.dur(60 * 60000L), "1 小时");
        eq("dur 3h20", BattText.dur((3 * 60 + 20) * 60000L), "3 小时 20 分");

        // ---- 主数字 ----
        eq("hero 38", BattText.hero(38 * 60000L), "38");
        eq("hero 单位 分钟", BattText.heroUnit(38 * 60000L), "分钟");
        eq("hero 1h20", BattText.hero(80 * 60000L), "1.3");
        eq("hero 单位 小时", BattText.heroUnit(80 * 60000L), "小时");
        eq("hero 12h", BattText.hero(12 * 3600000L), "12");
        eq("hero 0", BattText.hero(0), "0");

        // ---- 折算率 ----
        eq("rate 样本不足", BattText.rate(3, 60000L), "");
        eq("rate 零掉电", BattText.rate(0, 3600000L), "不足 0.1");
        eq("rate 1%/h", BattText.rate(1, 3600000L), "1.0");
        eq("rate 2h3%", BattText.rate(3, 2 * 3600000L), "1.5");

        // ---- 每月/每年折算 ----
        ok("estHours 30 天", Math.abs(BattText.estHours(6 * 3600000L, 3, 30) - 60.0) < 0.01);
        ok("estHours days=0 不炸", BattText.estHours(3600000L, 0, 30) == 0.0);
        eq("hours 0", BattText.hours(0), "0");
        eq("hours 0.3", BattText.hours(0.3), "0.3");
        eq("hours 12.5", BattText.hours(12.5), "12.5");
        eq("hours 146", BattText.hours(146.4), "146");

        System.out.println("  BatteryText pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
