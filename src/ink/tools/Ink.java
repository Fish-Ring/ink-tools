package ink.tools;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * 纯黑白绘制原语。
 *
 * 刻意关闭抗锯齿：EPD 上抗锯齿会生成灰阶边缘，既加重残影又浪费刷新。
 * 状态标记以描边 / 细线 / 勾笔画为主；仅本月打卡汇总表的小圆点用实心表示完成
 * （用户明确要求，点径极小，残影影响可忽略），其余一律不用实心填充与反白黑底。
 */
public final class Ink {
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;

    /** 前景 / 背景：OLED 模式下一键反色（黑底白字），墨水屏保持黑字白底。 */
    public static int FG = BLACK;
    public static int BG = WHITE;

    public static void theme(boolean dark) {
        FG = dark ? WHITE : BLACK;
        BG = dark ? BLACK : WHITE;
    }

    private Ink() {}

    public static Paint text(float size, boolean bold, boolean mono) {
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setColor(FG);
        p.setTextSize(size);
        Typeface tf = mono
                ? Typeface.MONOSPACE
                : (bold ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                        : Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTypeface(tf);
        return p;
    }

    public static Paint stroke(float width) {
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setColor(FG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        return p;
    }

    public static void line(Canvas c, float x1, float y, float x2, float w) {
        c.drawLine(x1, y, x2, y, stroke(w));
    }

    public static void box(Canvas c, float l, float t, float r, float b, float w) {
        c.drawRect(l, t, r, b, stroke(w));
    }

    /** 细线勾，避免实心勾选块。x/y 为方框左上角，s 为边长。 */
    public static void check(Canvas c, float x, float y, float s, float w) {
        c.drawLine(x + s * 0.14f, y + s * 0.52f, x + s * 0.40f, y + s * 0.78f, stroke(w));
        c.drawLine(x + s * 0.40f, y + s * 0.78f, x + s * 0.86f, y + s * 0.20f, stroke(w));
    }

    /** 实心填充画笔：仅用于汇总表的完成圆点。 */
    public static Paint fill() {
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setColor(FG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    /** 完成 = 实心，未完成 = 空心描边（外径与实心一致，排列时不会显得缺一个）。 */
    public static void dot(Canvas c, float cx, float cy, float r, boolean filled) {
        if (filled) c.drawCircle(cx, cy, r, fill());
        else {
            float w = Math.max(1f, r * 0.44f);
            c.drawCircle(cx, cy, Math.max(0.5f, r - w * 0.5f), stroke(w));
        }
    }
}
