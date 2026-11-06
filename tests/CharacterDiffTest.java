/**
 * 字符级脏区核心逻辑单测：验证 ClockText.diff() 只返回变化字符的索引区间。
 * 零依赖，可直接编译运行：
 *   javac -d /tmp/ctest src/ink/tools/ClockText.java tests/CharacterDiffTest.java
 *   java  -cp /tmp/ctest ink.tools.CharacterDiffTest
 */
package ink.tools;

public class CharacterDiffTest {
    static int pass = 0, fail = 0;

    static void check(String a, String b, String exp) {
        int[] d = ClockText.diff(a, b);
        String got = (d == null) ? "none" : (d[0] + "," + d[1]);
        if (got.equals(exp)) { pass++; System.out.println("OK   " + a + " -> " + b + " : " + got); }
        else { fail++; System.out.println("FAIL " + a + " -> " + b + " : got " + got + ", expect " + exp); }
    }

    public static void main(String[] args) {
        check("13:33", "13:33", "none");       // 相同 → 完全不刷新
        check("13:33", "13:34", "4,4");        // 只脏最后一个字符
        check("13:59", "14:00", "1,4");        // 首位 1 未变
        check("09:59", "10:00", "0,4");        // 整行变化
        check("13:33", "13:33:00", "0,7");     // 长度变化 → 整段
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
