package ink.tools;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 习惯 id 的编号规则。
 *
 * id 形如 "h1"、"h2"……：它是习惯的稳定标识——改名后历史跟着新名字走，
 * 习惯删掉了历史还在（记录页用归档里的旧名兜底）。
 *
 * 最容易出事的地方是**导入备份**：备份里的 id 是文件说了算，设备自己的计数器可能更小。
 * 老备份里的习惯没有 id 字段，若直接拿设备计数器发号，发出来的号可能正好被备份里
 * 另一个习惯占着 → 两个习惯同 id → 打卡历史串到错的习惯上。
 * 所以发号前必须先把「已经被占用的号」登记一遍，从它们之上发。
 *
 * 纯逻辑，不 import android.*，桌面 JVM 可直接单测（Store 依赖 android.*，测不了）。
 */
public final class HabitIds {

    private HabitIds() {
    }

    /** "h12" → 12；不是这个格式（"h"、"h1x"、"abc"…）或不是正数就返回 0（当作没编号）。 */
    public static int num(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'h') return 0;
        try {
            int n = Integer.parseInt(id.substring(1));
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 一组 id 里最大的编号；没有有效编号返回 0。 */
    public static int maxNum(Iterable<String> ids) {
        int max = 0;
        if (ids == null) return 0;
        for (String id : ids) {
            int n = num(id);
            if (n > max) max = n;
        }
        return max;
    }

    /**
     * 归档里所有记录的最大编号。
     * 归档里的习惯可能已被删除，但号仍算被占用：新号不能复用它们。
     */
    public static int maxNumOfMonths(ArrayList<HabitArchive.Month> months) {
        int max = 0;
        if (months == null) return 0;
        for (HabitArchive.Month m : months) {
            for (HabitArchive.Entry e : m.entries) {
                int n = num(e.id);
                if (n > max) max = n;
            }
        }
        return max;
    }

    /**
     * 发新号的起点：max(设备计数器, 已占用最大编号) + 1。
     *
     * 一个已占用的号都没有时沿用设备计数器（老备份全员缺 id 时不必跳号）。
     *
     * @param deviceNext  设备上「下一个要发的编号」（Store.nextHabitId）
     * @param reservedMax 已被占用的最大编号（备份里的习惯 + 归档里的历史）
     */
    public static int nextFree(int deviceNext, int reservedMax) {
        if (reservedMax <= 0) return Math.max(1, deviceNext);
        return Math.max(deviceNext, reservedMax) + 1;
    }

    /**
     * 给一批习惯发号：空串表示「备份里缺 id」，补一个新号；已占用的号原样保留。
     * 备份里自己就有重复 id 时，后一个也换成新号（否则两个习惯同 id）。
     *
     * @param fileIds    备份里读到的 id（缺 id 的位置是空串），顺序与习惯一致
     * @param deviceNext 设备上「下一个要发的编号」
     * @param reservedMax 别的来源已占用的最大编号（归档里的历史习惯）
     * @return 同序同长的新 id 列表
     */
    public static ArrayList<String> assign(ArrayList<String> fileIds, int deviceNext, int reservedMax) {
        ArrayList<String> out = new ArrayList<>();
        if (fileIds == null) return out;
        int reserved = Math.max(maxNum(fileIds), reservedMax);
        int next = nextFree(deviceNext, reserved);
        HashSet<String> used = new HashSet<>();
        for (String id : fileIds) {
            String v = id == null ? "" : id;
            if (v.isEmpty() || used.contains(v)) {
                v = "h" + (next++);        // next 一定大于所有已占用编号，不会撞车
            }
            used.add(v);
            out.add(v);
        }
        return out;
    }
}
