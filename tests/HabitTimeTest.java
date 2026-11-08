/**
 * 打卡时间编解码单测：0:00 与「未打卡」不撞车、旧位串迁移、坏数据不抛。
 *   javac -d /tmp/ht src/ink/tools/HabitTime.java tests/HabitTimeTest.java
 *   java  -cp /tmp/ht ink.tools.HabitTimeTest
 */
package ink.tools;

public class HabitTimeTest {
    static int pass = 0, fail = 0;

    static void eq(String what, long got, long exp) {
        if (got == exp) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void eq(String what, String got, String exp) {
        if (got.equals(exp)) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    public static void main(String[] args) {
        // ---- 编解码 ----
        eq("00:00 编成 1（不与未打卡撞车）", HabitTime.encode(0, 0), 1);
        eq("00:00 解回", HabitTime.hhmm(HabitTime.encode(0, 0)), "00:00");
        eq("07:05 编", HabitTime.encode(7, 5), 426);
        eq("07:05 解", HabitTime.hhmm(426), "07:05");
        eq("23:59 编", HabitTime.encode(23, 59), 1440);
        eq("未打卡显示空串", HabitTime.hhmm(HabitTime.NONE), "");
        eq("未知时间显示空串", HabitTime.hhmm(HabitTime.UNKNOWN), "");
        eq("越界小时被夹住", HabitTime.encode(99, 99), 1440);

        // ---- 数组编解码 ----
        int[] a = new int[32];
        a[1] = HabitTime.encode(6, 30);
        a[31] = HabitTime.UNKNOWN;
        String s = HabitTime.encodeArr(a);
        int[] b = HabitTime.decode(s);
        eq("往返：1 号", b[1], 391);
        eq("往返：31 号（未知）", b[31], HabitTime.UNKNOWN);
        eq("往返：2 号空", b[2], 0);
        eq("串里共 31 个值", s.split(",").length, 31);

        // ---- 坏数据 ----
        eq("空串 → 全 0", HabitTime.decode("")[5], 0);
        eq("null → 全 0", HabitTime.decode(null)[5], 0);
        eq("乱码当 0（不抛）", HabitTime.decode("1,x,3")[2], 0);
        eq("少值不越界", HabitTime.decode("1,2")[5], 0);

        // ---- 旧数据迁移：'1' 变 UNKNOWN，'0' 变 NONE ----
        int[] m = HabitTime.migrate("1010000000000000000000000000000");
        eq("迁移：1 号未知时间", m[1], HabitTime.UNKNOWN);
        eq("迁移：2 号未打卡", m[2], HabitTime.NONE);
        eq("迁移：3 号未知时间", m[3], HabitTime.UNKNOWN);
        eq("迁移：空串安全", HabitTime.migrate(null)[9], 0);

        System.out.println("\npass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
