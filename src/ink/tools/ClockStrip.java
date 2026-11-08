package ink.tools;

/**
 * 时钟页「数字下方两行信息」的排版。
 *
 * 倒数日是用户在倒数页主动打开的，优先级高于农历 / 电量那行（后者只是点数字轮换的附赠信息）。
 * 所以倒数占第一行、农历电量退到第二行；第二行放不下就整行隐藏，绝不叠字，也不许越过内容底边。
 *
 * 纯函数，不碰 Android：调用方把像素步长、字高量好传进来。
 */
public final class ClockStrip {

    public static final class Layout {
        public final float countBase;
        public final float metaBase;

        Layout(float countBase, float metaBase) {
            this.countBase = countBase;
            this.metaBase = metaBase;
        }
    }

    private ClockStrip() {
    }

    /**
     * @param firstY   第一行基线（cy + S(82) 那一带）
     * @param rowH     两行之间的行距
     * @param floor    内容可用底边（floorY 再留一点）
     * @param desc     文字 descent（正数，基线以下的墨迹）
     * @param wantCount 用户是否开启了「时钟页倒数」
     * @param wantMeta  农历 / 电量那行是否有内容
     * @return 两行基线；不画的行返回 -1
     */
    public static Layout layout(float firstY, float rowH, float floor, float desc,
                                boolean wantCount, boolean wantMeta) {
        // 第一行本身放不下：两行都不画
        if (firstY + desc > floor) return new Layout(-1f, -1f);
        if (wantCount) {
            boolean meta = wantMeta && firstY + rowH + desc <= floor;
            return new Layout(firstY, meta ? firstY + rowH : -1f);
        }
        return new Layout(-1f, wantMeta ? firstY : -1f);
    }
}
