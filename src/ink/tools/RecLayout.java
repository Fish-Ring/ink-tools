package ink.tools;

/**
 * 记录页的纵向预算计算。
 *
 * 记录页有固定的页头 + 范围键行 + 头部信息行，剩下的高度才是列表的。
 * 这些行的高度都跟着字号放大，所以大字号档每页能放的行数会明显变少
 * （3.1 档只剩 5 行）。这个计算以前散在 Screen 里，改一处就可能挤爆页面，
 * 抽成纯函数后可以直接断言。
 *
 * 纯逻辑，不 import android.*，桌面 JVM 可测。
 */
public final class RecLayout {

    private RecLayout() {
    }

    /** 每一页最多放多少行。 */
    public static int rowsPerPage(float contentTop, float pageBottom, float rowH, float gap) {
        if (rowH <= 0) return 1;
        int n = (int) ((pageBottom - gap - contentTop) / rowH);
        return n < 1 ? 1 : n;
    }

    /**
     * 打卡记录页的每页行数。
     *
     * @param u        密度单位
     * @param zoom     字号缩放
     * @param tabH     导航栏高度
     * @param screenH  屏高
     */
    public static int habitRowsPerPage(float u, float zoom, float tabH, float screenH) {
        float c19 = 19 * u * Math.min(zoom, 1.5f);
        float c26 = 26 * u * Math.min(zoom, 1.5f);
        float c20 = 20 * u * Math.min(zoom, 1.5f);
        float f = 24 * u * zoom;
        float bh = Math.max(26 * u, 10 * u * Math.min(zoom, 1.5f) * 1.8f);
        // 信息行里的小方框键（月份筛选）与次按钮同高；没有键时这一行照样占同样的高度，
        // 这样切范围、切月份都不会让下面的列表整体上下跳一下
        float chipH = bh;

        // 页头（标题 + 分隔线）→ 范围键行 → 习惯分段键行 → 信息行
        float y = 18 * u + c19 + c26;
        y += c20;
        y += bh + 14 * u * zoom;          // 范围键行 + 间距
        y += bh + 10 * u * zoom;          // 习惯分段键行（全部 / 早睡 / 早起…）+ 间距
        y += chipH + 6 * u * zoom;        // 信息行（条数 + 月份键）

        float pageY = screenH - tabH - 26 * u;
        return rowsPerPage(y, pageY, f, 8 * u);
    }
}
