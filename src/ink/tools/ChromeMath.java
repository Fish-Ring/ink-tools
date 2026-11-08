package ink.tools;

/**
 * 「界面部件」（页头标题、日期行、导航栏、按钮、行内小字）的字号换算。
 *
 * 正文必须跟着字号档位放大——墨水屏离眼远，用户调大就是要看清；
 * 但页头、日期、按钮属于 chrome：3.1 档下若也放大 2.38 倍，页头块要占 13.7% 屏高、
 * 日期行占 8.3%，把真正的内容（时钟、列表、置顶倒数）全挤掉。
 *
 * 所以 chrome 封顶 {@link #CHROME_MAX} 倍（与 S() 对线宽的封顶同值）。
 * 1.3 档 zoom=1，封顶不生效，等于原始设计稿。
 */
public final class ChromeMath {
    /** chrome 最大放大倍数。3.1 档（zoom=2.385）实际用到 1.5，页头 95px → 60px。 */
    public static final float CHROME_MAX = 1.5f;

    /**
     * 导航栏单独放宽到 1.9 倍：它只有 7 个字，横向冲突检测已经兜住，
     * 跟正文一样封顶 1.5 会让「调了字号导航栏几乎没反应」。
     */
    public static final float NAV_MAX = 1.9f;

    private ChromeMath() {
    }

    public static float clampZoom(float zoom) {
        if (zoom < 1f) return 1f;               // 档位下限 1.3，zoom 不会小于 1，防御性收敛
        return Math.min(zoom, CHROME_MAX);
    }

    /** chrome 文字/几何的像素值。 */
    public static float px(float dp, float u, float zoom) {
        return dp * u * clampZoom(zoom);
    }

    /** 内容字号：不封顶，跟着档位走。 */
    public static float contentPx(float dp, float u, float zoom) {
        return dp * u * zoom;
    }

    /**
     * 导航文字：跟随字号（比 chrome 放宽），但仍受「一格放得下最宽标签」的硬约束。
     * per 是 1px 字号对应的字宽（由 Paint 量出），cell 是单格可用宽度。
     */
    public static float navPx(float dp, float u, float zoom, float cell, float per) {
        float want = dp * u * Math.min(Math.max(zoom, 1f), NAV_MAX);
        float max = per > 0 ? cell / per : want;
        return Math.max(9f * u, Math.min(want, max));
    }

    /**
     * 导航栏高度：跟着导航文字走，而不是被 42*u 的下限锁死。
     * 下限保证小字号也有可点的高度；上限 15% 屏高防止吃掉正文。
     */
    public static float navH(float u, float navPx, float screenH) {
        return Math.min(Math.max(40f * u, navPx * 2.6f), screenH * 0.15f);
    }
}
