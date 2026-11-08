package ink.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 记录页的「分组 + 明细」行模型。
 *
 * 需求：记录不能拍平成一张表——各习惯要分开、各月份要分开，
 * 否则「早睡」和「跑步」的行互相穿插，看不出谁是谁。
 *
 * 模型：一条明细行带一串**上下文分组**（chain），从外到内。
 * 例如「2026 年 10 月 → 早睡 → 11-05 打卡」，chain = [月, 习惯]。
 * 渲染时按 chain 逐层补标题；翻页时若某层标题不在本页，就在本页顶部补一次，
 * 保证每条明细都能看出它属于谁。
 *
 * 分组用**序号**而不是标题文本判定：两个习惯可能重名，
 * 用文本判会把两个不同的组当成同一个。约定：外层用负数、内层用非负数。
 *
 * 纯逻辑，不 import android.*，桌面 JVM 可测。
 */
public final class RecGroup {

    public static final int GROUP = 0;
    public static final int ITEM = 1;

    /** 一行。 */
    public static final class Row {
        public int kind;              // GROUP / ITEM
        public int gid = -1;          // GROUP：它引入的分组序号
        public int[] chain;           // ITEM：上下文分组序号（外 → 内）
        public String title = "";     // GROUP：标题文本
        public String left = "";      // ITEM：左列
        public String note = "";      // ITEM：中列（可空，打卡记录用来放习惯名）
        public String right = "";     // ITEM：右列
        public int payload = -1;      // ITEM：关联数据的下标（点击用）

        Row(int kind) { this.kind = kind; }

        public static Row group(int gid, String title) {
            Row r = new Row(GROUP);
            r.gid = gid;
            r.title = title;
            return r;
        }

        public static Row item(int[] chain, String left, String right, int payload) {
            Row r = new Row(ITEM);
            r.chain = chain;
            r.left = left;
            r.right = right;
            r.payload = payload;
            return r;
        }

        /** 三列明细：日期 / 说明 / 时刻。 */
        public static Row item(int[] chain, String left, String note, String right, int payload) {
            Row r = item(chain, left, right, payload);
            r.note = note == null ? "" : note;
            return r;
        }
    }

    /** 一页：若干行（可能包含补出来的标题行）。 */
    public static final class Page {
        public final ArrayList<Row> lines = new ArrayList<>();
    }

    private RecGroup() {
    }

    /**
     * 分页：产出全部页。
     *
     * 规则：
     * - 一页最多 per 行；
     * - 明细行的上下文标题若不在本页，就在本页顶部补齐（外层先补、内层后补）；
     * - 补标题要占位置：本页放不下就先翻页，让标题落到新页顶部；
     * - 任何一行都不会丢，也不会死循环。
     *
     * 退化情形：一行放不下「所有标题 + 明细」时（per 很小），
     * 优先保留**最内层**标题（它最能说明这条属于谁）；per < 2 时不补标题。
     */
    public static ArrayList<Page> paginate(ArrayList<Row> items, Map<Integer, String> titles, int per) {
        ArrayList<Page> pages = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            pages.add(new Page());
            return pages;
        }
        if (per < 1) per = 1;
        Page cur = new Page();
        int i = 0;
        while (i < items.size()) {
            Row r = items.get(i);
            if (r.kind == GROUP) {
                if (cur.lines.size() >= per) {
                    pages.add(cur);
                    cur = new Page();
                }
                cur.lines.add(r);
                i++;
                continue;
            }
            int[] use = usableChain(r.chain, per);
            // 本页放不下（含要补的标题）→ 先翻页，标题落到新页顶部
            if (cur.lines.size() + countMissing(cur, use) + 1 > per) {
                pages.add(cur);
                cur = new Page();
            }
            for (int id : use) {
                if (hasGroup(cur, id)) continue;
                String t = titles == null ? null : titles.get(id);
                cur.lines.add(Row.group(id, t == null ? "" : t));
            }
            cur.lines.add(r);
            i++;
        }
        if (!cur.lines.isEmpty()) pages.add(cur);
        if (pages.isEmpty()) pages.add(new Page());
        return pages;
    }

    /**
     * 这一页能用的标题链：标题 + 明细必须放得下 per 行。
     * 放不下全部时退到最内层（最内层最能说明这条属于谁）。
     */
    static int[] usableChain(int[] chain, int per) {
        if (chain == null || chain.length == 0) return new int[0];
        if (chain.length + 1 <= per) return chain;
        if (per >= 2) return new int[]{chain[chain.length - 1]};
        return new int[0];
    }

    /** 本页还缺几个标题。 */
    private static int countMissing(Page p, int[] ids) {
        int n = 0;
        for (int id : ids) if (!hasGroup(p, id)) n++;
        return n;
    }

    /** 某分组在本页是否已出现。 */
    public static boolean hasGroup(Page p, int gid) {
        for (Row r : p.lines) {
            if (r.kind == GROUP && r.gid == gid) return true;
        }
        return false;
    }

    /** 建标题表：gid → 标题文本。 */
    public static Map<Integer, String> titles() {
        return new HashMap<>();
    }
}
