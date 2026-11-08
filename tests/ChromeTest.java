/**
 * chrome 字号封顶 + 台历顶部降级决策的单测。
 *   javac -d /tmp/ct src/ink/tools/ChromeMath.java src/ink/tools/CalHead.java tests/ChromeTest.java
 *   java  -cp /tmp/ct ink.tools.ChromeTest
 */
package ink.tools;

public class ChromeTest {
    static int pass = 0, fail = 0;

    static void eq(String what, float got, float exp) {
        if (Math.abs(got - exp) < 0.1f) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    static void eq(String what, int got, int exp) {
        if (got == exp) { pass++; System.out.println("OK   " + what + " = " + got); }
        else { fail++; System.out.println("FAIL " + what + " = " + got + "，期望 " + exp); }
    }

    public static void main(String[] args) {
        float u = 2.335f;                       // 汉王 PA6 实测

        // ---- chrome 封顶 ----
        eq("1.3 档 zoom=1 不封顶", ChromeMath.clampZoom(1f), 1f);
        eq("1.75 档 zoom=1.346 不封顶", ChromeMath.clampZoom(1.3461539f), 1.3461539f);
        eq("3.1 档 zoom=2.385 封到 1.5", ChromeMath.clampZoom(3.1f / 1.3f), 1.5f);
        eq("页头 17dp @3.1 = 59.5px", ChromeMath.px(17f, u, 3.1f / 1.3f), 59.5f);
        eq("页头 17dp @1.3 = 39.7px", ChromeMath.px(17f, u, 1f), 39.7f);
        // 正文不封顶：3.1 档 13dp 仍按 2.385 倍
        eq("正文 13dp @3.1 = 72.4px", ChromeMath.contentPx(13f, u, 3.1f / 1.3f), 72.4f);

        // ---- 台历顶部降级 ----
        // 宽屏：日期全称 + 置顶全称
        CalHead.Layout a = CalHead.layout(400, 260, 200, 90, 120, 80, 12, true);
        eq("宽屏 date", a.date, CalHead.DATE_FULL);
        eq("宽屏 pin", a.pin, CalHead.PIN_FULL);

        // 只剩 90px：全称放不下（260+12+120=392>400? 392<=400 放得下；这里用 380）
        CalHead.Layout b = CalHead.layout(380, 260, 200, 90, 120, 80, 12, true);
        eq("窄一点：日期不动、置顶只留天数 date", b.date, CalHead.DATE_FULL);
        eq("窄一点：置顶只留天数 pin", b.pin, CalHead.PIN_TAIL);

        // 更窄：全称日期 + 天数也放不下 → 去掉星期
        CalHead.Layout c = CalHead.layout(300, 260, 200, 90, 120, 80, 12, true);
        eq("去星期 date", c.date, CalHead.DATE_NO_WEEK);
        eq("去星期 pin", c.pin, CalHead.PIN_TAIL);

        // 再窄：只有最短日期 + 天数能放
        CalHead.Layout d = CalHead.layout(200, 260, 200, 90, 120, 80, 12, true);
        eq("最短日期 date", d.date, CalHead.DATE_MIN);
        eq("最短日期 pin", d.pin, CalHead.PIN_TAIL);

        // 连最短也放不下：置顶另起一行
        CalHead.Layout e = CalHead.layout(150, 260, 200, 90, 120, 80, 12, true);
        eq("换行 date", e.date, CalHead.DATE_FULL);
        eq("换行 pin", e.pin, CalHead.PIN_NEWLINE);

        // 行也放不下：置顶不画
        CalHead.Layout f = CalHead.layout(150, 260, 200, 90, 120, 80, 12, false);
        eq("不画 pin", f.pin, CalHead.PIN_NONE);

        // 边界：刚好相等也算放得下
        CalHead.Layout g = CalHead.layout(392, 260, 200, 90, 120, 80, 12, true);
        eq("边界 date", g.date, CalHead.DATE_FULL);
        eq("边界 pin", g.pin, CalHead.PIN_FULL);

        System.out.println("\npass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
