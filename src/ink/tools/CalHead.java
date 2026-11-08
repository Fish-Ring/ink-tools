package ink.tools;

/**
 * 台历顶部一行的排版决策：左边日期，右边置顶倒数，两者抢同一行的宽度。
 *
 * 用户实际反馈的是「日期太大把右边的倒数挤掉了」——根因是日期跟着字号放大，
 * 4 个字变成 8 个字宽。这里把「先缩日期、再砍置顶、最后换行」的顺序写成纯函数，
 * 可以脱离 Canvas 单测，避免每次改字号都要靠肉眼试。
 */
public final class CalHead {

    /** 日期形态：0 全称（含星期）/ 1 去掉星期 / 2 最短（月/日）。 */
    public static final int DATE_FULL = 0, DATE_NO_WEEK = 1, DATE_MIN = 2;
    /** 置顶形态：0 名字 + 天数 / 1 只留天数 / 2 另起一行 / 3 本行不画。 */
    public static final int PIN_FULL = 0, PIN_TAIL = 1, PIN_NEWLINE = 2, PIN_NONE = 3;

    public static final class Layout {
        public final int date;
        public final int pin;

        Layout(int date, int pin) {
            this.date = date;
            this.pin = pin;
        }
    }

    private CalHead() {
    }

    /**
     * 大小字共排一行时，基线相同会让小字视觉上「沉」在下面（墨迹中心比大字低）。
     * 按两者字高差把小字基线上移，使墨迹中心一致。0.34 是 sans 字体
     * (|ascent| - descent) / 2 / size 的近似，纯排版补偿，不涉及具体字体度量。
     */
    public static float alignBaseline(float refBase, float refPx, float smallPx) {
        return refBase - 0.34f * (refPx - smallPx);
    }

    /**
     * @param avail       一行可用宽度
     * @param wFull       完整日期宽度
     * @param wNoWeek     去掉星期后的日期宽度
     * @param wMin        最短日期宽度
     * @param wPinFull    「名字 · 还有 N 天」宽度
     * @param wPinTail    「还有 N 天」宽度
     * @param gap         日期与置顶之间的最小间距
     * @param canNewLine  垂直方向还够不够置顶另起一行
     */
    public static Layout layout(float avail, float wFull, float wNoWeek, float wMin,
                                float wPinFull, float wPinTail, float gap, boolean canNewLine) {
        if (avail - wFull - gap >= wPinFull) return new Layout(DATE_FULL, PIN_FULL);
        float[] dw = {wFull, wNoWeek, wMin};
        for (int i = 0; i < dw.length; i++) {
            if (avail - dw[i] - gap >= wPinTail) return new Layout(i, PIN_TAIL);
        }
        // 日期再怎么缩也挤不出置顶的位置：先保证日期完整，置顶另起一行；行也放不下就不画
        return new Layout(DATE_FULL, canNewLine ? PIN_NEWLINE : PIN_NONE);
    }
}
