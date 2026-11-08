package ink.tools;

/**
 * 台历页「时钟盒」的垂直排版：数字必须在方框内按**真实墨迹**居中。
 *
 * 旧实现把基线写死成 boxTop + (boxH - lunarH) * 0.64，那是个拍脑袋的比例：
 * 字体 ascent/descent 不一样、农历行有没有文字也不一样，结果数字总是偏上，
 * 下方留一大块空白。这里把「可用区」和「墨迹中心」两件事拆开，纯函数可测。
 *
 * 不 import android.*：Paint 的 ascent/descent 由调用方量好传进来。
 */
public final class ClockBox {

    private ClockBox() {
    }

    /**
     * 数字可用的纵向区间 {top, bottom}。
     * 有农历时，农历行墨迹顶部以上要再留 gap；没有农历时数字独占整个盒子。
     *
     * @param lunarInkTop 农历行墨迹顶部（= 农历基线 - 农历 ascent），无农历时忽略
     */
    public static float[] digitArea(float boxT, float boxB, boolean hasLunar,
                                    float lunarInkTop, float gap) {
        float top = boxT + gap;
        float bottom = (hasLunar ? lunarInkTop - gap : boxB - gap);
        if (bottom < top) bottom = top;
        return new float[]{top, bottom};
    }

    /** 数字字号：按可用高度反算，ratio 留给 descent 与呼吸空间。 */
    public static float digitPx(float areaH, float ratio) {
        return Math.max(1f, areaH * ratio);
    }

    /**
     * 让墨迹（baseline - ascent .. baseline + descent）在 [top, bottom] 中垂直居中的基线。
     * ascent / descent 都传**正数**：ascent = -paint.ascent(), descent = paint.descent()。
     */
    public static float baseline(float top, float bottom, float ascent, float descent) {
        return (top + bottom) / 2f + (ascent - descent) / 2f;
    }

    /** 墨迹中心，供单测与调试核对。 */
    public static float inkCenter(float baseline, float ascent, float descent) {
        return baseline + (descent - ascent) / 2f;
    }

    /**
     * 主数字按可用宽度等比收缩。抽成纯函数是为了钉住**幂等性**：
     * 台历盒先用它算字号、再用该字号量 ascent/descent 定基线，
     * 如果绘制时又缩一次，基线就会和真实字形错开。
     */
    public static float fitPx(float wantPx, float textW, float maxW) {
        if (maxW <= 0 || textW <= 0) return Math.max(1f, wantPx);
        return textW > maxW ? Math.max(1f, wantPx * maxW / textW) : Math.max(1f, wantPx);
    }
}
