package ink.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashMap;

/**
 * 整个应用只有这一个自定义 View：所有界面用 Canvas 自绘。
 *
 * 墨水屏策略：
 *  - 默认「时:分」，调度对齐整分边界，每分钟只唤醒一次；
 *  - 时间文本逐字符比较，只 invalidate 变化字符的矩形（字符级脏区）；
 *  - 静态区块永不单独重绘；页面切换 / 交互后整屏重绘一次，由系统决定波形；
 *  - 严格 #000 / #fff，关闭抗锯齿；状态以描边与细线为主，仅汇总表小圆点用实心表示完成；
 *  - 波形选择、累计清残影、刷新模式全部交给系统，App 不干预。
 */
public class Screen extends View {

    static final int DASH = 0, CLOCK = 1, POMO = 2, TODO = 3,
            HABIT = 4, DAYS = 5, SET = 6, TIPS = 7,
            BATT = 8,                      // 电量统计页（不进导航栏，从设置进）
            HIST_HABIT = 9, HIST_TODO = 10,   // 记录页（打卡 / 清单）
            HIST_TODO_DETAIL = 11;             // 清单记录详情页
    static final String[] TABS = {"台历", "时钟", "番茄", "清单", "打卡", "倒数", "设置"};
    private static final String[] REC_RANGES = {"本月", "上月", "全部"};
    private static final String[] HREC_RANGES = {"本周", "本月", "上月", "全部"};

    private final MainActivity act;
    private final Store st;

    private float W, H, u, pad, tabH, zoom = 1f, navPx;
    private int mode = DASH;
    private final ArrayList<Hit> hits = new ArrayList<>();

    // 当前页面主时间文本的绘制参数，用于计算脏区
    private boolean hasTime;
    private float tX, tCW, tBase, tAsc, tDesc;
    private String timeStr = "";

    private int clockMeta = 0;                 // 0 农历 / 1 电量 / 2 无
    private float clockMetaBase = -1;          // 时钟页农历/电量行的实际基线（-1 = 本帧没画）
    private int battShown = Integer.MIN_VALUE; // 上一次画出的电量，只用于比对是否要重绘那一行
    private int battTab = 0;                   // 电量页口径：0 今日 / 1 充电后 / 2 近 7 天 / 3 全部
    private int battPage = 0;                  // 电量键值表翻页（表格放不下时翻页而不是直接省略行）
    private int recRange = 0;                  // 清单记录范围：0 本月 / 1 上月 / 2 全部
    private int recMonth;                      // 清单记录「全部」档下的月份筛选（yyyyMM，0 = 不筛）
    private int hrecScope = HabitScope.MONTH;  // 打卡记录范围：0 本周 / 1 本月
    private int hrecMonth;                     // 打卡记录「全部」档下的月份筛选（yyyyMM，0 = 不筛）
    private int hrecHabit = HabitScope.ALL_HABITS; // 打卡记录习惯筛选（-1 全部）
    private int recPage = 0;                   // 清单记录翻页
    private int hrecPage = 0;                  // 打卡记录翻页（独立，避免两页串页码）
    private int todoDetail = -1;               // 清单记录详情页：当前查看的记录下标（-1 = 未打开）
    private boolean dayFromClock;              // 倒数页是否由时钟页倒数行进入（返回时回时钟）
    private final android.content.IntentFilter battFilter =
            new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
    private int pomoPhase = 0;                 // 0 专注 / 1 短休 / 2 长休
    private int pomoRound = 1;
    private boolean pomoRunning = false, pomoCustom = true;
    private boolean pomoHist;                  // 番茄页是否在看历史列表
    private int pomoHistPage;                  // 历史列表翻页
    private int pomoHistRange = 0;             // 历史范围：0 本月 / 1 上月 / 2 全部
    private long pomoEndAt = 0, pomoLeftMs;
    private int todoScope = 0;                 // 0 今天 / 1 全部
    private int todoOpenPage, todoDonePage;    // 清单双列各自翻页
    private int gridHabit = 0;                 // 打卡页当前选中的习惯（内存态）
    private boolean habitEdit;                 // 月历是否处于「补记」态（默认只读）
    private int dashTodoPage, dashDayPage;     // 台历双栏翻页
    private int dashRowsPer = 1;               // 台历双栏本次算出的每页行数
    private long lastDay;                     // 跨天检测
    private long lastBattCheck;               // 上次读电量的时刻（毫秒），限流用

    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private boolean started;
    private Runnable longRun;
    private boolean longFired;
    private float downX, downY;

    Screen(MainActivity a, Store s) {
        super(a);
        act = a;
        st = s;
        pomoLeftMs = st.pomo.focus * 60000L;
        setWillNotDraw(false);
    }

    // ---------------------------------------------------------------- 尺寸

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        W = w; H = hh;
        recalc();
    }

    private void recalc() {
        if (W <= 0 || H <= 0) return;
        // 设计画布 420×620 只由屏幕决定；字号是**整体缩放**乘在上面（1.3 档 = 1.0）。
        // 早先只有文字跟着字号变，而盒子、行高、导航栏都不变，于是「字放大了、框没放大」，
        // 到处都是挤压和重叠；小的说明字反而涨到和标题一样大，层级彻底乱掉。
        // 现在文字与几何共用同一个 zoom，比例恒定，放大等于整页放大，放不下的模块直接不画。
        u = Math.min(W / 420f, H / 620f);
        zoom = st.fontScale / 1.3f;
        pad = 18 * u;
        navPx = navTextPx();
        // 高度跟着导航文字走：以前下限写死 42*u，七档字号下 tabH 恒定不变，
        // 用户看到的就是「调了字号，底下那条菜单纹丝不动」。
        tabH = ChromeMath.navH(u, navPx, H);
    }

    /**
     * 导航栏文字：跟随字号放大（封顶比通用 chrome 宽），但**先做横向冲突检测**——
     * 7 个格子，最宽的标签必须放得进一格，否则会被截成「台…时…番…」。
     */
    private float navTextPx() {
        if (W <= 0) return 0;
        // 和 drawTabs 用同一个内边距
        float cell = W / 7f - navPad() * 2;
        // 绘制选中项用的是粗体，测量也必须是粗体，否则宽度判断会偏小
        float per = Ink.text(100, true, false).measureText("台历") / 100f;
        return ChromeMath.navPx(12, u, zoom, cell, per);
    }

    /** 导航栏每格左右内边距：不随字号放大，导航是固定 UI。 */
    private float navPad() { return 6 * u; }

    /** 内容可用底边：导航栏上沿再留一点空隙，所有页面都用它当预算。 */
    private float floorY() { return H - tabH - MIN(4); }

    /** ≤3 的数值几乎都是线宽与极小内缩：跟着放大只会让线条又粗又脏。 */
    private float S(float dp) {
        if (dp <= 3f) return dp * u * Math.min(zoom, 1.5f);
        return dp * u * zoom;
    }

    /** 正文字号：与几何同一个单位，层级比例恒定。 */
    private float F(float dp) { return dp * u * zoom; }

    /**
     * chrome 字号：页头标题、日期行、导航、按钮、行内小字。
     * 随字号放大但封顶 1.5×——3.1 档下页头 17dp 若也乘 2.38，就是 95px、占 13.7% 屏高，
     * 把时钟和列表全挤掉，这就是「一放大就比例失调」的来源。
     */
    private float C(float dp) { return ChromeMath.px(dp, u, zoom); }

    private void TC(Canvas c, String s, float x, float y, float dp, boolean bold, boolean mono) {
        c.drawText(s, x, y, Ink.text(C(dp), bold, mono));
    }

    private float CW(String s, float dp, boolean bold, boolean mono) {
        return Ink.text(C(dp), bold, mono).measureText(s);
    }

    /** 与 pick() 同理，但按 chrome 字号量宽。 */
    private String pickC(String full, String brief, float dp, float maxW, boolean bold) {
        return CW(full, dp, bold, false) <= maxW ? full : brief;
    }

    /** 固定字号的旧入口，现在与 F 一致（保留是为了少改调用点）。 */
    private float P(float dp) { return dp * u * zoom; }

    // ---------------------------------------------------------------- 调度

    void start() {
        started = true;
        timeStr = "";
        lastDay = Store.today();
        if (st.rolloverIfNeeded()) st.save();
        // 启动即采一次，别等第一次 tick——否则刚打开就看「电量统计」会是空的
        lastBattCheck = System.currentTimeMillis();
        // 进前台先采一次：跟上一次的间隔如果超过 100 秒，这一步会自动判成「离开」，
        // 只滑动基点、不把这段时间算进使用时长
        st.battTick(battery(), charging());
        cancelLong();
        schedule();
        invalidate();
    }

    void stop() {
        started = false;
        if (ticker != null) h.removeCallbacks(ticker);
        cancelLong();
        // 退到后台前再采一次，把最后那不到一分钟也算上；下次回来间隔过大就自动判离开
        st.battTick(battery(), charging());
    }

    private void schedule() {
        if (!started) return;
        if (ticker != null) h.removeCallbacks(ticker);
        Calendar c = Calendar.getInstance();
        long delay;
        if (pomoRunning || st.showSeconds) delay = 1000L - c.get(Calendar.MILLISECOND) + 20;
        else delay = (60L - c.get(Calendar.SECOND)) * 1000L - c.get(Calendar.MILLISECOND) + 20;
        if (delay < 20) delay = 20;
        ticker = new Runnable() {
            @Override public void run() { tick(); schedule(); }
        };
        h.postDelayed(ticker, delay);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long t = Store.today();
        boolean rolled = (t != lastDay);
        if (rolled) {
            lastDay = t;
            if (st.rolloverIfNeeded()) st.save();
        }
        String clock = ClockText.time(now, st.h24, st.showSeconds);
        String main = (mode == POMO) ? pomoText() : clock;
        if (rolled) { timeStr = main; invalidate(); }
        // 历史列表页没有时间区域，不能走字符级局部重绘，否则会闪在列表上
        else if (!pomoHist && (mode == DASH || mode == CLOCK || mode == POMO)) refreshTime(main);
        else timeStr = main;
        if (pomoRunning) checkPomo();

        // 电量搭「时间跳变」的顺风车核对：55 秒一次，显示秒时也不会每秒去读电池。
        // 只有整数百分比变了才重绘农历/电量那条横带——以前这里不刷，电量要手动点一下才更新。
        if (now - lastBattCheck >= 55000L) {
            lastBattCheck = now;
            int b = battery();
            if (b >= 0 && b != battShown && mode == CLOCK && clockMeta == 1) {
                battShown = b;
                invalidateMetaRow();
            }
            st.battTick(b, charging());
            // 明细页正开着就顺手刷一次，否则要退出去再进来才看得到新的一段
        }
    }

    /** 只重绘时钟页「农历 / 电量」那一条横带，不动大数字，避免整屏闪。 */
    private void invalidateMetaRow() {
        if (W <= 0 || H <= 0 || clockMetaBase <= 0) return;
        float y = clockMetaBase;
        invalidate((int) pad, (int) (y - F(18)), (int) (W - pad), (int) (y + F(6)));
    }

    /** 只让变化的字符失效，避免整屏重绘。 */
    private void refreshTime(String s) {
        String old = timeStr;
        if (!hasTime) { timeStr = s; return; }
        boolean lenChanged = old.length() != s.length();
        int[] d = ClockText.diff(old, s);
        timeStr = s;
        if (d == null) return;
        if (lenChanged) { invalidate(); return; }
        // 余量跟着字号走：3.1 档的字高是标准档的 2.4 倍，固定 3u 会把字形边缘
        // 留在脏区外面，墨水屏上表现为上一分钟的残影
        float m = Math.max(3 * u, F(3));
        float l = tX + d[0] * tCW - m;
        float r = tX + (d[1] + 1) * tCW + m;
        float t = tBase + tAsc - m;
        float b = tBase + tDesc + m;
        invalidate((int) l, (int) t, (int) r, (int) b);
    }

    // ---------------------------------------------------------------- 番茄钟

    /** 自定义时长的四项边界：工作 5–90、短休 1–30、长休 5–60、轮数 2–8。 */
    private static final int[] CFG_LO = {5, 1, 5, 2}, CFG_HI = {90, 30, 60, 8};
    private static final String[] CFG_NAME = {"工作", "短休", "长休", "轮数"};

    private int pomoCfgCur(int which) {
        switch (which) {
            case 0: return st.pomo.focus;
            case 1: return st.pomo.shortBreak;
            case 2: return st.pomo.longBreak;
            default: return st.pomo.rounds;
        }
    }

    /**
     * 写入一项自定义时长，自动夹到合法区间。
     *
     * 网格的 +/− 和摘要态弹窗都走这里：两处各写一遍边界与「暂停时同步倒计时」，
     * 迟早会不一致（以前就是只有网格那条路径会同步 pomoLeftMs）。
     */
    private void applyPomoCfg(int which, int raw) {
        if (which < 0 || which > 3) return;
        int v = Math.max(CFG_LO[which], Math.min(CFG_HI[which], raw));
        switch (which) {
            case 0:
                st.pomo.focus = v;
                // 未开始 / 已暂停的专注阶段：数字立刻跟着改，不然改了时长还要等下一轮
                if (pomoPhase == 0 && !pomoRunning) pomoLeftMs = v * 60000L;
                break;
            case 1: st.pomo.shortBreak = v; break;
            case 2: st.pomo.longBreak = v; break;
            default: st.pomo.rounds = v; break;
        }
        st.save();
        invalidate();
    }

    /** 摘要态弹窗改一项：选完项再填数字，比连点十几次 + 快得多。 */
    private void askPomoCfg(final int which) {
        if (which < 0 || which > 3) return;
        String hint = CFG_LO[which] + "–" + CFG_HI[which] + " 之间";
        act.input(CFG_NAME[which] + "时长", hint, String.valueOf(pomoCfgCur(which)),
                new MainActivity.Cb() {
                    @Override public void run(String s) {
                        try {
                            applyPomoCfg(which, Integer.parseInt(s.trim()));
                        } catch (NumberFormatException e) {
                            act.toast("请输入数字");
                        }
                    }
                });
    }

    private int phaseMinutes() {
        return pomoPhase == 0 ? st.pomo.focus
                : (pomoPhase == 1 ? st.pomo.shortBreak : st.pomo.longBreak);
    }

    private String phaseName() {
        return pomoPhase == 0 ? "专注" : (pomoPhase == 1 ? "短休息" : "长休息");
    }

    private String pomoText() {
        long left = pomoRunning ? (pomoEndAt - SystemClock.elapsedRealtime()) : pomoLeftMs;
        if (left < 0) left = 0;
        int min = (int) ((left + 59999) / 60000);
        return ClockText.two(min) + ":00";
    }

    private void nextPhase(boolean auto) {
        if (pomoPhase == 0) {
            // 专注结束 = 完成第 pomoRound 轮。到目标轮数进长休，否则短休。
            boolean last = st.pomo.rounds > 0 && pomoRound >= st.pomo.rounds;
            pomoPhase = last ? 2 : 1;
            if (!last) pomoRound++;
        } else {
            pomoPhase = 0;
            // 长休结束：一轮会话做完，回到第 1 轮；短休结束则保持下一轮编号
            if (st.pomo.rounds > 0 && pomoRound >= st.pomo.rounds) pomoRound = 1;
        }
        pomoLeftMs = phaseMinutes() * 60000L;
        pomoRunning = false;
        if (auto) {
            pomoEndAt = SystemClock.elapsedRealtime() + pomoLeftMs;
            pomoRunning = true;
        }
    }

    private void checkPomo() {
        if (!pomoRunning) return;
        if (SystemClock.elapsedRealtime() >= pomoEndAt) {
            pomoRunning = false;
            if (pomoPhase == 0) st.notePomoDone(st.pomo.focus);
            nextPhase(true);
            if (mode == POMO) invalidate();   // 其他页面不因后台阶段切换而整屏刷新
        }
    }

    // ---------------------------------------------------------------- 绘制原语

    private void T(Canvas c, String s, float x, float y, float dp, boolean bold, boolean mono) {
        c.drawText(s, x, y, Ink.text(F(dp), bold, mono));
    }

    private float TW(String s, float dp, boolean bold, boolean mono) {
        return Ink.text(F(dp), bold, mono).measureText(s);
    }

    /** 超长文本按可用宽度截断，避免画到右侧按钮上。 */
    private String fit(String s, float dp, float maxW, boolean bold, boolean mono) {
        if (maxW <= 0) return "";
        Paint p = Ink.text(F(dp), bold, mono);
        if (p.measureText(s) <= maxW) return s;
        int n = p.breakText(s, true, maxW, null);
        return s.substring(0, Math.max(1, n - 1)) + "…";
    }

    /** 用**像素**字号直接画：格子里的星期 / 数字属于图形元素，不跟随字号设置。 */
    private void TP(Canvas c, String s, float x, float y, float px, boolean bold) {
        c.drawText(s, x, y, Ink.text(px, bold, false));
    }

    private float PW(String s, float px, boolean bold) {
        return Ink.text(px, bold, false).measureText(s);
    }

    /** 像素字号 + 等宽（导航栏、设置页这些受固定行高约束的文字用）。 */
    private void TPx(Canvas c, String s, float x, float y, float px, boolean bold, boolean mono) {
        c.drawText(s, x, y, Ink.text(px, bold, mono));
    }

    private float PWx(String s, float px, boolean bold, boolean mono) {
        return Ink.text(px, bold, mono).measureText(s);
    }

    /** 按像素字号截断，语义同 fit()。字符串都很短，直接逐字试宽。 */
    private String fitP(String s, float px, float maxW, boolean bold, boolean mono) {
        if (maxW <= 0) return "";
        if (PWx(s, px, bold, mono) <= maxW) return s;
        int n = 1;
        while (n < s.length() && PWx(s.substring(0, n + 1) + "…", px, bold, mono) <= maxW) n++;
        return s.substring(0, Math.max(1, n)) + "…";
    }

    /**
     * 按钮内文字的统一入口：字号用 C(dp)（与 btnH/btnSmH 同一套 chrome 封顶），
     * 基线按**真实墨迹**在按钮内垂直居中。
     *
     * 以前每处按钮都写 `y + h/2 + F(dp)*0.36`：按钮高度用 C（封顶 1.5×），
     * 文字却用 F（不封顶），3.1 档文字涨到按钮的 1.6 倍，直接顶穿上边框。
     * 现在所有线框按钮共用这一个方法，字号与排版不会再各写一套。
     */
    private void BTN(Canvas c, String s, float l, float top, float w, float h,
                     float dp, boolean bold, boolean mono) {
        float px = Math.min(C(dp), h * 0.62f);
        Paint p = Ink.text(px, bold, mono);
        String t = fitP(s, px, w - S(6), bold, mono);
        float x = l + w / 2f - p.measureText(t) / 2f;
        c.drawText(t, x, ClockBox.baseline(top, top + h, -p.ascent(), p.descent()), p);
    }

    /** 按钮文字宽度（与 BTN 同字号），给调用方留左右空间用。 */
    private float BTNW(String s, float dp, boolean bold, boolean mono) {
        return Ink.text(C(dp), bold, mono).measureText(s);
    }

    /**
     * 放不下就换短文案。
     * 设计约束要求「空间不足优先降级：减内容、缩短文案」，而不是把字缩到看不清。
     */
    private String pick(String full, String brief, float dp, float maxW, boolean bold) {
        return TW(full, dp, bold, false) <= maxW ? full : brief;
    }

    private void CT(Canvas c, String s, float y, float dp, boolean bold, boolean mono) {
        T(c, s, (W - TW(s, dp, bold, mono)) / 2f, y, dp, bold, mono);
    }

    private void HL(Canvas c, float x1, float x2, float y) {
        Ink.line(c, x1, y, x2, S(2));
    }

    private void BOX(Canvas c, float l, float t, float r, float b, float w) {
        Ink.box(c, l, t, r, b, S(w));
    }

    private void HIT(String tag, int idx, float l, float t, float r, float b) {
        hits.add(new Hit(l, t, r, b, tag, idx));
    }

    // ---------------------------------------------------------------- 统一度量
    // 全应用只留两档按钮高度与一套页头，避免每页各写一套（以前是 32/34/36/38/46 混用）。

    /**
     * 最小触控高度：**不跟随字号**。写成 S(34) 时它自己也放大，3.1 档下按钮变成 190px 高，
     * 一个按钮就吃掉半个屏幕——「不该大的地方反而更大」就是这一类。
     */
    private float MIN(float dp) { return dp * u; }

    /** 主按钮高度：页面底部操作条、番茄三键都用它，纵向预算也用它算。 */
    private float btnH() { return Math.max(MIN(34), C(11) * 1.85f); }

    /** 次按钮高度：翻页、筛选、导出、行内「改 / 删」。 */
    private float btnSmH() { return Math.max(MIN(26), C(10) * 1.8f); }

    /**
     * 统一页头：17 粗标题 + 右侧 12 等宽值 + 分隔线。
     * 返回标题基线（与旧代码一致），各页在它之后自己加内容间距。
     */
    private float head(Canvas c, String title, String right) {
        float y = pad + C(19);
        TC(c, title, pad, y, 17, true, false);
        if (right != null && !right.isEmpty()) {
            TC(c, right, W - pad - CW(right, 12, false, true), y, 12, false, true);
        }
        HL(c, pad, W - pad, y + C(8));
        return y;
    }

    /** 带右侧文字点击区的页头（返回 / 历史 / 版本号旁边的入口）。 */
    private float head(Canvas c, String title, String right, String tag, int idx) {
        float y = head(c, title, right);
        if (right != null && !right.isEmpty()) {
            float w = CW(right, 12, false, true);
            HIT(tag, idx, W - pad - w - S(14), y - C(17), W - pad, y + C(8));
        }
        return y;
    }

    /** 主数字按可用宽度反算后的最终字号；盒内居中必须用**这个**字号的度量。 */
    private float mainPx(String s, float wantPx) {
        return ClockBox.fitPx(wantPx, Ink.text(wantPx, true, true).measureText(s),
                W - 2 * pad - S(14));
    }

    private void drawMainTime(Canvas c, String s, float px, float baseline, float cx) {
        // px 必须是调用方用 mainPx() 收缩过的最终值：这里不再二次收缩，
        // 否则台历盒按 px 量好的居中基线会和实际绘制的字号差一点。
        Paint p = Ink.text(px, true, true);
        float total = p.measureText(s);
        float cw = total / Math.max(1, s.length());
        float x = cx - total / 2f;
        c.drawText(s, x, baseline, p);
        hasTime = true;
        timeStr = s;
        tX = x; tCW = cw; tBase = baseline; tAsc = p.ascent(); tDesc = p.descent();
    }

    private float wrap(Canvas c, String s, float x, float y, float maxW, float dp, boolean bold) {
        Paint p = Ink.text(F(dp), bold, false);
        int start = 0;
        while (start < s.length()) {
            int n = p.breakText(s, start, s.length(), true, maxW, null);
            if (n <= 0) n = 1;
            c.drawText(s, start, start + n, x, y, p);
            y += F(dp + 7);
            start += n;
        }
        return y;
    }

    /** 日期去掉星期：「11 月 8 日」。宽度不够时的第一级降级。 */
    private String dateNoWeek(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
    }

    /** 最短日期：「11/8」。留给置顶倒数的最低限度。 */
    private String dateMin(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
    }

    private String dateText(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        String[] wk = {"日", "一", "二", "三", "四", "五", "六"};
        int dow = c.get(Calendar.DAY_OF_WEEK) - 1;
        if (dow < 0 || dow > 6) dow = 0;
        // 竖屏宽度只有 420dp，带年份的长日期会把右上角「还有 N 天」挤没，短格式等价可读
        if (W < H) {
            return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH)
                    + " 日 · 周" + wk[dow];
        }
        return c.get(Calendar.YEAR) + " 年 " + (c.get(Calendar.MONTH) + 1) + " 月 "
                + c.get(Calendar.DAY_OF_MONTH) + " 日 · 星期" + wk[dow];
    }

    private String dateShort(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) + " / " + ClockText.two(c.get(Calendar.MONTH) + 1)
                + " / " + ClockText.two(c.get(Calendar.DAY_OF_MONTH));
    }

    /** 电量读取是跨进程调用，别在每帧绘制里做：缓存 10 秒。 */
    private int battCache = -2;          // -2 = 尚未读过
    private long battCacheAt;

    private int battery() {
        long now = System.currentTimeMillis();
        if (battCache != -2 && now - battCacheAt < 10000L) return battCache;
        int v = -1;
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                v = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Throwable ignored) {
        }
        battCache = v;
        battCacheAt = now;
        return v;
    }

    /** 是否正在充电。用粘性广播读，零权限；读不到就当没充。 */
    private boolean charging() {
        try {
            android.content.Intent i = act.registerReceiver(null, battFilter);
            if (i == null) return false;
            int s = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || s == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }
    /** 设置页那一行的值：尽量短，大字号下也不能把左边文字挤掉。 */
    private String battText() {
        int cur = battery();
        if (st.battCharging) return cur >= 0 ? "充电 " + cur + "%" : "充电中";
        BattMath.Acc a = st.battToday;
        if (!a.seeded() || a.ms < 60000L) return cur >= 0 ? cur + "%" : "—";
        long h = a.ms / 3600000L, m = (a.ms % 3600000L) / 60000L;
        // 两位数小时就省掉分钟：这一行是右对齐的，太长会顶到左边的「电量统计」上
        String d = h >= 10 ? h + "h" : (h > 0 ? h + "h" + (m > 0 ? m + "m" : "") : m + "m");
        return d + " · " + (a.drop > 0 ? a.drop + "%" : "<1%");
    }

    /**
     * 电量弹窗的一页。i：0 今日 / 1 本次充电后 / 2 近 7 天。
     * 三条口径都是「有效使用」：只有两次采样间隔够近的时间才计入，
     * 离开期间掉的电不算——但会把基点挪到当前，免得回来第一分钟被算成消耗。
     *
     * 期间充过电时不再画「90% → 89%」那种箭头：起点已经挪到充电后，
     * 箭头会和累计掉电对不上，改成一句文字说明。
     */
    String battPage(int i) {
        int cur = battery();
        if (cur < 0) return "这台设备读不到电量，无法统计。";
        if (i == 2) {
            long ms = st.batt7Ms();
            int drop = st.batt7Drop();
            if (ms < 60000L) return "近 7 天还没有足够的记录。\n\n开着放几分钟就会开始累计。";
            StringBuilder b = new StringBuilder();
            b.append("近 7 天　使用 ").append(durText(ms)).append('\n');
            b.append("耗电　　").append(drop > 0 ? drop + "%" : "不足 1%");
            addRate(b, drop, ms);
            b.append("\n\n只统计 App 在前台的时段，离开期间掉的电不算。");
            return b.toString();
        }
        boolean cg = (i == 1);
        if (cg && st.battCharging) {
            return "正在充电。\n\n拔掉电源后从这里重新起算，充电期间不计消耗。";
        }
        BattMath.Acc a = cg ? st.battCharge : st.battToday;
        if (!a.seeded() || a.start <= 0) {
            return cg
                    ? "还没有数据。\n\n充过电之后从这里重新起算，充电期间不计消耗。"
                    : "还没有数据。\n\n开着放几分钟就会开始累计。";
        }
        StringBuilder b = new StringBuilder();
        b.append(cg ? "本次充电后" : "今日").append(' ').append(hhmm(a.start)).append(" 起\n");
        b.append("使用　　").append(durText(a.ms)).append('\n');
        b.append("耗电　　").append(a.drop > 0 ? a.drop + "%" : "不足 1%");
        if (a.charged) {
            b.append("（期间充过电）");
        } else if (a.drop > 0) {
            int from = a.from >= 0 ? a.from : a.pct + a.drop;
            b.append("（").append(from).append("% → ").append(a.pct).append("%）");
        }
        addRate(b, a.drop, a.ms);
        if (st.battCharging) b.append("\n\n正在充电，先暂停累计。");
        b.append("\n\n上次记录　").append(hhmm(a.last)).append(" · ").append(a.pct).append("%");
        return b.toString();
    }

    /** 折算成 %/小时。样本不足 5 分钟不给——1% 的粒度下那个数字会离谱。 */
    private void addRate(StringBuilder b, int drop, long ms) {
        double rate = BattMath.perHour(drop, ms);
        if (rate < 0) return;
        String v = rate < 0.05
                ? "不足 0.1"
                : String.format(java.util.Locale.US, "%.1f", rate);
        b.append("\n折算　　").append(v).append("% / 小时");
    }

    private static String durText(long ms) {
        long m = ms / 60000L;
        if (m < 1) return "不到 1 分钟";
        if (m < 60) return m + " 分钟";
        long h = m / 60, r = m % 60;
        return r == 0 ? h + " 小时" : h + " 小时 " + r + " 分";
    }


    private static String stamp(long ms) {
        if (ms <= 0) return "—";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return ClockText.two(c.get(Calendar.MONTH) + 1) + "-"
                + ClockText.two(c.get(Calendar.DAY_OF_MONTH)) + " "
                + ClockText.two(c.get(Calendar.HOUR_OF_DAY)) + ":"
                + ClockText.two(c.get(Calendar.MINUTE));
    }

    private static String dur(int min) {
        return min < 60 ? min + " 分"
                : (min / 60) + "." + ((min % 60) * 10 / 60) + " 时";
    }

    /**
     * 行内一对「改 / 删」线框小按钮，返回按钮区左边界。
     * 全应用统一一套尺寸：以前台历/清单各写一套（23×20 与 19×17），同一屏上两种大小。
     */
    private float ops(Canvas c, float y, float edge, String editTag, String delTag, int idx) {
        float bw = Math.max(MIN(19), F(9) + S(9)), bh = Math.max(MIN(17), F(9) + S(8));
        float by = y - bh + F(4);
        float dl = edge - bw, el = dl - bw - S(3);
        BOX(c, el, by, el + bw, by + bh, 1);
        BTN(c, "改", el, by, bw, bh, 9, false, false);
        BOX(c, dl, by, dl + bw, by + bh, 1);
        BTN(c, "删", dl, by, bw, bh, 9, false, false);
        HIT(editTag, idx, el, by, el + bw, by + bh);
        HIT(delTag, idx, dl, by, dl + bw, by + bh);
        return el - S(3);
    }

    // ---------------------------------------------------------------- onDraw

    @Override
    protected void onDraw(Canvas c) {
        // 字号在设置里改完只会 invalidate：这里补一次度量重算，导航栏高度才会跟着变
        if (u == 0 || Math.abs(zoom - st.fontScale / 1.3f) > 1e-4f) recalc();
        Ink.theme(st.oled);                    // OLED 模式整屏反色（黑底白字）
        c.drawColor(Ink.BG);
        hits.clear();
        hasTime = false;
        // 硬兜底：页面内容一律不许画进底部导航栏。
        // 各页虽然都按剩余高度算过，但字号 + 横屏 + 展开组合起来总有算漏的时候，
        // 裁一刀比逐页打补丁可靠——压到菜单栏上是最刺眼的一种错。
        c.save();
        c.clipRect(0f, 0f, W, Math.max(0f, H - tabH));
        switch (mode) {
            case DASH:  drawDashboard(c); break;
            case CLOCK: drawClock(c);      break;
            case POMO:  drawPomodoro(c);   break;
            case TODO:  drawTodo(c);       break;
            case HABIT: drawHabits(c);     break;
            case DAYS:  drawDays(c);       break;
            case SET:   drawSettings(c);   break;
            case TIPS:  drawTips(c);       break;
            case BATT:  drawBatt(c);       break;
            case HIST_HABIT: drawHabitRecords(c); break;
            case HIST_TODO:  drawTodoRecords(c);  break;
            case HIST_TODO_DETAIL: drawTodoRecordDetail(c); break;
            default: break;
        }
        c.restore();
        drawTabs(c);
    }

    private void drawTabs(Canvas c) {
        float y = H - tabH;
        HL(c, 0, W, y);
        float w = W / 7f;
        // 字号由 navTextPx() 决定：跟随设置放大，但已经按格宽做过冲突检测，
        // 所以这里不需要再截断（截断只会在极端窄屏上兜底）。
        float ts = navPx;
        for (int i = 0; i < 7; i++) {
            boolean on = (mode == i);
            String s = fitP(TABS[i], ts, w - navPad() * 2, on, false);
            TPx(c, s, i * w + w / 2f - PWx(s, ts, on, false) / 2f,
                    y + tabH / 2f + ts * 0.36f, ts, on, false);
            if (on) Ink.line(c, i * w + w * 0.18f, H - S(3), i * w + w * 0.82f, S(3));
            if (i > 0) {
                Paint p = Ink.stroke(S(1));
                c.drawLine(i * w, y, i * w, H, p);
            }
            HIT("tab", i, i * w, y, (i + 1) * w, H);
        }
    }

    // ---------------------------------------------------------------- 台历主页

    /** 今天还未完成的待办数（只用于算双栏要几行）。 */
    private int todayTodoCount() {
        int n = 0;
        for (Store.Todo t : st.todos) if (t.day == Store.today()) n++;
        return n;
    }

    private float colHeight(int rows) {
        return F(30) + rows * F(21) + F(22);
    }

    private void drawDashboard(Canvas c) {
        long now = System.currentTimeMillis();
        // 日期行属于 chrome：封顶 1.5×（3.1 档 60px，而正文 13dp 是 72px，层级还清楚）。
        // 右侧置顶倒数原本是 11 号细等宽，和左边 17 号粗体差得太远，看着像两行不相干的字；
        // 现在统一成 13 号粗体非等宽，并按墨迹中心对齐基线。
        float datePx = 17, pinPx = 13;
        float dateBase = pad + C(15);
        float pinBase = CalHead.alignBaseline(dateBase, C(datePx), C(pinPx));
        float pinGap = C(12);

        Store.Day pin = st.pinnedDay();
        String tail = "";
        String pinFull = "";
        if (pin != null) {
            tail = dayDeltaText(Store.daysFrom(pin.millis));
            pinFull = pin.name + " · " + tail;
        }
        String dFull = dateText(now), dNoWk = dateNoWeek(now), dMin = dateMin(now);
        // 降级顺序写进 CalHead（有单测）：完整日期+全名 → 只留天数 → 日期去星期 → 日期最短 → 置顶换行
        CalHead.Layout lay = CalHead.layout(W - 2 * pad,
                CW(dFull, datePx, true, false), CW(dNoWk, datePx, true, false),
                CW(dMin, datePx, true, false),
                pin == null ? 0f : CW(pinFull, pinPx, true, false),
                pin == null ? 0f : CW(tail, pinPx, true, false),
                pinGap, true);

        String dt = lay.date == CalHead.DATE_MIN ? dMin
                : lay.date == CalHead.DATE_NO_WEEK ? dNoWk : dFull;
        TC(c, dt, pad, dateBase, datePx, true, false);

        float pinRow = 0;
        if (pin != null) {
            String s;
            float y;
            boolean newLine = lay.pin == CalHead.PIN_NEWLINE;
            if (lay.pin == CalHead.PIN_FULL) {
                s = pinFull; y = pinBase;
            } else if (lay.pin == CalHead.PIN_TAIL) {
                s = tail; y = pinBase;
            } else if (newLine) {
                pinRow = C(24);
                s = pickC(pinFull, tail, pinPx, W - 2 * pad, true);
                y = dateBase + pinRow;
            } else {
                s = null; y = pinBase;
            }
            if (s != null) {
                // 同一行贴右边缘；换行时改成与左侧日期同左边缘，两行才排得齐
                float x = newLine ? pad : W - pad - CW(s, pinPx, true, false);
                TC(c, s, x, y, pinPx, true, false);
                // 换行时点击区只包住文字；同一行时整条日期行都可点（旧行为）
                float hitL = newLine ? pad - S(4) : pad;
                float hitR = newLine ? pad + CW(s, pinPx, true, false) + S(4) : W - pad;
                HIT("tab", DAYS, hitL, y - C(14), hitR, y + C(5));
            }
        }
        float clockTop = dateBase + C(10) + pinRow;

        // ---------------- 预算 ----------------
        // 四块（双栏 / 本周概览 / 习惯胶囊 / 番茄行）在竖屏里是硬约束：
        // 先把各自的自然高度量出来，再从「时钟」开始压，压不动才按优先级丢模块。
        // 这样任何字号下都不会有两块叠在一起，也不会出现被截断的省略号。
        float gapBase = S(10);
        float lineH = F(11) * 1.9f;
        float chipRowH = Math.max(S(24), F(10) * 1.9f);
        int chipLines = Math.min(2, habitChipLines());
        int stripMode = weekStripMode();               // 2 七格 / 1 一行 / 0 不画
        boolean pomo = true;
        int colRows = Math.max(1, Math.min(7, Math.max(todayTodoCount(), st.days.size())));

        float bottom = floorY();
        float clockH = S(88);
        int guard = 0;
        while (guard++ < 40) {
            float total = clockTop + clockH + S(12) + colHeight(colRows)
                    + (stripMode == 2 ? weekCellBlockH() : stripMode == 1 ? lineH : 0)
                    + chipLines * chipRowH + (chipLines - 1) * S(4)
                    + (pomo ? lineH : 0) + gapBase * 4;
            if (total <= bottom) break;
            // 先按优先级丢模块，丢不动了才收时钟：
            // 反过来做的话，3.1 档会出现「时钟被压小 + 模块也丢了」两头都亏
            if (stripMode == 2) { stripMode = 1; continue; }
            if (chipLines > 1) { chipLines = 1; continue; }
            if (stripMode == 1) { stripMode = 0; continue; }
            if (pomo) { pomo = false; continue; }
            if (colRows > 1) { colRows--; continue; }
            if (clockH > S(64)) { clockH -= S(4); continue; }
            break;                                     // 能丢的都丢了，只能这样
        }

        float colH = colHeight(colRows);
        float stripH = stripMode == 2 ? weekCellBlockH() : stripMode == 1 ? lineH : 0;
        float chipsH = chipLines * chipRowH + (chipLines - 1) * S(4);
        float pomoH = pomo ? lineH : 0;
        int blocks = 1 + (stripH > 0 ? 1 : 0) + (chipLines > 0 ? 1 : 0) + (pomo ? 1 : 0);

        // 剩下的一点空白摊到各段间距里，再把时钟放大一档：
        // 「大面积留白」和「底部空一块」都是排版失衡，用留白换字号更符合这块屏的用途
        float used = clockTop + clockH + S(12) + colH + stripH + chipsH + pomoH
                + gapBase * blocks;
        float slack = Math.max(0f, bottom - used);
        float extra = Math.min(S(26), slack / (blocks + 2f));
        clockH += extra * 2f;
        // 剩下的空间给时钟（上限再加 42dp）：它是这页的主角，
        // 与其在底部留一大片白，不如把数字做大——这也正是「该大的地方不够大」。
        float rest = slack - extra * (blocks + 2f);
        if (rest > 0) clockH += Math.min(rest, S(42));
        float gap = gapBase + extra;

        // ---------------- 时钟盒 ----------------
        float boxT = clockTop, boxB = clockTop + clockH;
        BOX(c, pad, boxT, W - pad, boxB, 2);
        String ts = ClockText.time(now, st.h24, st.showSeconds);
        String lun = st.lunar ? Lunar.text(now) : null;
        boolean hasLunar = lun != null && !lun.isEmpty();
        Paint lp = hasLunar ? Ink.text(Math.min(F(11), S(13)), false, false) : null;
        float lunarBase = boxB - S(8);
        // ascent 是负数：墨迹顶 = 基线 + ascent
        float lunarInkTop = hasLunar ? lunarBase + lp.ascent() : 0f;
        float[] area = ClockBox.digitArea(boxT, boxB, hasLunar, lunarInkTop, S(4));
        float px = mainPx(ts, ClockBox.digitPx(area[1] - area[0], 0.82f));
        Paint digitPaint = Ink.text(px, true, true);
        float base = ClockBox.baseline(area[0], area[1],
                -digitPaint.ascent(), digitPaint.descent());
        drawMainTime(c, ts, px, base, W / 2f);
        if (hasLunar) {
            c.drawText(lun, W / 2f - lp.measureText(lun) / 2f, lunarBase, lp);
        }
        HIT("clock", 0, pad, boxT, W - pad, boxB);

        // ---------------- 内容：顺序往下排，各段之间用同一个 gap ----------------
        float y = boxB + gap;
        y = drawDashColumns(c, y, colRows);
        if (stripH > 0) {
            y += gap;
            if (stripMode == 2) drawWeekStrip(c, y, weekCellBlockH());
            else drawWeekLine(c, y + lineH * 0.74f);
            y += stripH;
        }
        if (chipLines > 0) {
            y += gap;
            drawHabitChips(c, y, chipLines, chipRowH);
            y += chipsH;
        }
        if (pomo) drawPomoLine(c, y + gap + lineH * 0.74f);
    }

    private static String dayDeltaText(long n) {
        if (n == 0) return "就是今天";
        return n > 0 ? "还有 " + n + " 天" : "已过 " + (-n) + " 天";
    }

    /**
     * 本周概览能画成几格：格子里要写「周一 2」，格子只有屏宽 1/7。
     * 字号放大后字会被格宽压回去，比正文小一大截——那种「缩小的字」本身就是不协调，
     * 所以宁可退化成一行「本周 3/14 次」，也不画七个读不清的格子。
     */
    private int weekStripMode() {
        if (st.habits.isEmpty()) return 0;
        float cell = (W - 2 * pad) / 7f;
        if (Math.min(F(9), cell / 4.6f) < F(9) * 0.82f) return 1;
        return 2;
    }

    /** 七格块的高度：标题一行 + 两行字高的格子 + 一点内边距。 */
    private float weekCellBlockH() { return F(9) * 2.6f + F(18) + S(10); }

    private String weekSummaryText() {
        int nh = st.habits.size();
        Calendar cal = Calendar.getInstance();
        int dom = cal.get(Calendar.DAY_OF_MONTH);
        int back = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        Calendar w = (Calendar) cal.clone();
        w.add(Calendar.DAY_OF_MONTH, -back);
        int done = 0, total = 0;
        for (int i = 0; i < 7; i++) {
            if (w.get(Calendar.MONTH) == cal.get(Calendar.MONTH)
                    && w.get(Calendar.YEAR) == cal.get(Calendar.YEAR)) {
                int d = w.get(Calendar.DAY_OF_MONTH);
                if (d <= dom) {
                    for (int k = 0; k < nh; k++) {
                        total++;
                        if (st.habits.get(k).days[d]) done++;
                    }
                }
            }
            w.add(Calendar.DAY_OF_MONTH, 1);
        }
        return "本周 " + done + "/" + total + " 次";
    }

    /** 退化版：一行「本周打卡 3/14 次 ›」，信息不丢，只是不再画格子。 */
    private void drawWeekLine(Canvas c, float base) {
        T(c, "本周打卡", pad, base, 11, true, false);
        String s = weekSummaryText().replace("本周 ", "") + " ›";
        T(c, s, W - pad - TW(s, 9.5f, false, true), base, 9.5f, false, true);
        HIT("tab", HABIT, pad, base - F(14), W - pad, base + F(6));
    }

    /**
     * 本周打卡概览：7 个小格，格内一个状态点（● 全部完成 / ○ 部分完成 / 空 未打卡）。
     * 只做趋势展示，点整条进打卡页补记；今日逐个打卡用下方「日常」行。
     */
    private void drawWeekStrip(Canvas c, float top, float blockH) {
        int nh = st.habits.size();
        if (nh == 0) return;
        Calendar cal = Calendar.getInstance();
        int dom = cal.get(Calendar.DAY_OF_MONTH);
        int mon = cal.get(Calendar.MONTH), year = cal.get(Calendar.YEAR);
        int back = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // 周一 = 0

        T(c, "本周打卡 ›", pad, top + F(10), 11, true, false);
        String s = weekSummaryText().replace("本周 ", "");
        T(c, s, W - pad - TW(s, 9.5f, false, true), top + F(10), 9.5f, false, true);

        float cellH = blockH - F(16) - S(2);
        float cy = top + F(16), cw = (W - 2 * pad) / 7f;
        String[] wk = {"一", "二", "三", "四", "五", "六", "日"};
        Calendar w = (Calendar) cal.clone();
        w.add(Calendar.DAY_OF_MONTH, -back);
        for (int i = 0; i < 7; i++) {
            float l = pad + i * cw, r = l + cw - S(3);
            BOX(c, l, cy, r, cy + cellH, 1);
            boolean inMonth = w.get(Calendar.MONTH) == mon && w.get(Calendar.YEAR) == year;
            int d = w.get(Calendar.DAY_OF_MONTH);
            if (inMonth) {
                float c1 = cy + cellH * 0.30f, c2 = cy + cellH * 0.74f;
                float gpx = Math.min(F(9), (r - l) / 4.6f);
                String top1 = "周" + wk[i] + " " + d;
                if (PW(top1, gpx, false) > r - l - S(3)) top1 = wk[i] + " " + d;
                TP(c, top1, (l + r) / 2f - PW(top1, gpx, false) / 2f, c1 + gpx * 0.36f, gpx, false);
                int cnt = 0;
                for (int k = 0; k < nh; k++) if (st.habits.get(k).days[d]) cnt++;
                String cs = cnt + "/" + nh;
                float csPx = Math.min(F(9), (r - l) / 4.2f);
                float cw2 = PW(cs, csPx, true);
                float cxm = (l + r - cw2 - S(8)) / 2f;
                Ink.dot(c, cxm, c2, Math.min(S(3f), (r - l) * 0.055f), cnt >= nh && nh > 0);
                TP(c, cs, cxm + S(8), c2 + csPx * 0.36f, csPx, true);
                if (d == dom) BOX(c, l + S(1), cy + S(1), r - S(1), cy + cellH - S(1), 1);
            } else {
                T(c, wk[i], (l + r) / 2f - TW(wk[i], 9, false, false) / 2f,
                        cy + cellH / 2f + F(9) * 0.36f, 9, false, false);
            }
            w.add(Calendar.DAY_OF_MONTH, 1);
        }
        HIT("tab", HABIT, pad, top, W - pad, cy + cellH + S(2));
    }

    /** 底部「日常 / 番茄」标签列宽：跟着字号走，大字号时不会顶到第一个胶囊。 */
    private float miniLabelW() {
        return TW("日常", 11, true, false) + S(9);
    }

    /** 习惯 chips 占几行：预估与绘制共用同一套宽度计算。 */
    private int habitChipLines() {
        int dom = Store.dayOfMonth();
        float avail = W - 2 * pad - miniLabelW(), cx = 0;
        int lines = 1;
        for (int i = 0; i < st.habits.size(); i++) {
            Store.Habit hb = st.habits.get(i);
            String s = hb.name + (hb.days[dom] ? " ✓" : " ○");
            float w = TW(s, 10, false, false) + S(8);
            if (cx + w > avail && cx > 0) { cx = 0; lines++; }
            cx += w;
        }
        return Math.min(3, Math.max(1, lines));
    }

    private static String dayShort(Store.Day d) {
        long n = Store.daysFrom(d.millis);
        if (n == 0) return "今天";
        return n > 0 ? n + "天" : (-n) + "天前";
    }

    /** 台历双栏：左「今日待办」可直接勾选，右「倒数日」列出各项，各自分页。 */
    private float drawDashColumns(Canvas c, float y, int rows) {
        ArrayList<Integer> ti = new ArrayList<>();
        int tDone = 0;
        for (int i = 0; i < st.todos.size(); i++) {
            Store.Todo t = st.todos.get(i);
            if (t.day != Store.today()) continue;
            ti.add(i);
            if (t.done) tDone++;
        }
        int dn = st.days.size();
        // 每页行数由 drawDashboard 预算好传进来（预算只算一次，避免两套算法打架）
        int per = Math.max(1, rows);
        dashRowsPer = per;
        float colH = colHeight(per);

        int tp = Math.max(1, (ti.size() + per - 1) / per);
        int dp = Math.max(1, (dn + per - 1) / per);
        if (dashTodoPage >= tp) dashTodoPage = 0;
        if (dashDayPage >= dp) dashDayPage = 0;

        float mid = W / 2f, gapCol = S(8), right = W - pad;
        c.drawLine(mid, y + F(2), mid, y + colH - F(2), Ink.stroke(S(1)));
        drawColumnHead(c, pad, y, mid - gapCol - pad, "今日待办", tDone + "/" + ti.size());
        drawColumnHead(c, mid + gapCol, y, right - mid - gapCol, "倒数日", dn + " 项");

        for (int k = 0; k < per; k++) {
            float ry = y + F(32) + k * F(21);
            int j = dashTodoPage * per + k;
            if (j < ti.size()) {
                int i = ti.get(j);
                dashTodoRow(c, pad, ry, st.todos.get(i), i, mid - gapCol);
            }
            int q = dashDayPage * per + k;
            if (q < dn) {
                Store.Day d = st.days.get(q);
                float lx = mid + gapCol;
                T(c, fit(d.name, 10.5f, right - lx - S(38), false, false), lx, ry, 10.5f, false, false);
                String sv = dayShort(d);
                T(c, sv, right - TW(sv, 9, false, true), ry, 9, false, true);
                HIT("tab", DAYS, lx, ry - F(13), right, ry + F(6));
            }
        }
        if (ti.isEmpty()) T(c, "暂无待办", pad, y + F(50), 10.5f, false, false);
        if (dn == 0) T(c, "暂无事件", mid + gapCol, y + F(50), 10.5f, false, false);

        if (tp > 1 || dp > 1) {
            float py = y + colH - F(14);
            if (tp > 1) colPageBtn(c, pad, py, mid - gapCol - pad, tp, dashTodoPage, 0);
            if (dp > 1) colPageBtn(c, mid + gapCol, py, right - mid - gapCol, dp, dashDayPage, 1);
        }
        return y + colH;
    }

    private void drawColumnHead(Canvas c, float x, float y, float w, String title, String cnt) {
        // 标题先按计数让位；让位后还放不下就用短标题，别留一个「今日…」
        float cw = TW(cnt, 10, false, true);
        String brief = title.length() > 2 ? title.substring(0, 2) : title;
        T(c, pick(title, brief, 12, w - cw - S(6), true), x, y + F(14), 12, true, false);
        T(c, cnt, x + w - cw, y + F(14), 10, false, true);
        HL(c, x, x + w, y + F(20));
    }

    private void dashTodoRow(Canvas c, float x, float y, Store.Todo t, int idx, float right) {
        float s = F(11), bt = y - s * 0.8f;
        BOX(c, x, bt, x + s, bt + s, 2);
        if (t.done) Ink.check(c, x, bt, s, S(2));
        float tx = x + s + F(6);
        String txt = fit(t.text, 10.5f, right - tx - S(2), false, false);
        T(c, txt, tx, y, 10.5f, false, false);
        if (t.done) Ink.line(c, tx, y - F(3), tx + TW(txt, 10.5f, false, false), S(2));
        HIT("dashtodo", idx, x, y - F(13), right, y + F(6));
    }

    private void colPageBtn(Canvas c, float x, float y, float w,
                            int pages, int page, int col) {
        if (pages <= 1) return;                 // 只有一页：不画页码框，它只会挡住表头
        String s = "‹ " + (page + 1) + "/" + pages + " ›";
        float bw = TW(s, 10, false, true) + S(14);
        float l = x + (w - bw) / 2f, t = y - F(12), b = y + F(6);
        BOX(c, l, t, l + bw, b, 1);
        T(c, s, l + S(7), y, 10, false, true);
        if (pages > 1) HIT("dashpg", col, l, t, l + bw, b);
    }

    /** 底部习惯胶囊：给定顶部与行数，逐行排；行数由预算决定，不在这里再折行。 */
    private void drawHabitChips(Canvas c, float top, int lines, float rowH) {
        int dom = Store.dayOfMonth();
        float lw = miniLabelW();
        float avail = W - 2 * pad - lw;
        T(c, "日常", pad, top + rowH * 0.72f, 11, true, false);
        float hx = pad + lw, cx = 0;
        int used = 1;
        for (int i = 0; i < st.habits.size(); i++) {
            Store.Habit x = st.habits.get(i);
            String s = x.name + (x.days[dom] ? " ✓" : " ○");
            float tw = TW(s, 10, false, false), w = tw + S(8);
            if (cx + w > avail && cx > 0) {
                if (used >= lines) break;
                cx = 0; hx = pad + lw; used++;
            }
            float hy = top + (used - 1) * (rowH + S(4)) + rowH * 0.72f;
            BOX(c, hx - S(2), hy - F(11), hx + tw + S(2), hy + F(3), 1);
            T(c, s, hx, hy, 10, false, false);
            HIT("dashhabit", i, hx - S(2), hy - F(11), hx + tw + S(2), hy + F(3));
            hx += w; cx += w;
        }
        if (st.habits.isEmpty()) T(c, "暂无习惯", pad + lw, top + rowH * 0.72f, 10, false, false);
    }

    private void drawPomoLine(Canvas c, float base) {
        String pv = (pomoRunning ? phaseName() + " " + pomoText() : "未开始")
                + " · 今日 " + st.pomoDoneToday + " 轮";
        T(c, "番茄", pad, base, 11, true, false);
        float lw = miniLabelW();
        T(c, fit(pv, 11, W - 2 * pad - lw, false, true), pad + lw, base, 11, false, true);
        HIT("tab", POMO, pad, base - F(13), W - pad, base + F(4));
    }

    // ---------------------------------------------------------------- 全屏时钟

    private void drawClock(Canvas c) {
        long now = System.currentTimeMillis();
        float cy = (H - tabH) / 2f;
        CT(c, dateText(now), cy - F(64), 14, false, false);
        String ts = ClockText.time(now, st.h24, st.showSeconds);
        // 数字与上下两行必须用同一套「固定」基准：数字本身不跟字号（S），
        // 基线若用 F(36) 会随字号下移，而农历那行 >=52 不跟，结果两个字叠在一起
        drawMainTime(c, ts, mainPx(ts, S(st.showSeconds ? 62 : 96)), cy + S(40), W / 2f);
        // 数字下沿到第一行文字上沿之间留出空隙，两个 HIT 不重叠，
        // 免得点数字时误触倒数、或点倒数时误切农历/电量档
        HIT("main", 0, pad, cy - S(110), W - pad, cy + S(64));

        String meta = "";
        if (clockMeta == 0) {
            if (st.lunar) {
                String l = Lunar.text(now);
                meta = (l == null) ? "" : l;
            }
        } else if (clockMeta == 1) {
            int b = battery();
            // 画一次就把当前值记下，tick 里才能判断「变了没有」，空白档不会误刷
            battShown = b >= 0 ? b : Integer.MIN_VALUE;
            meta = b >= 0 ? "电量 " + b + "%" : "电量 未知";
        }

        // 数字下方两行：倒数（用户主动开启）优先占第一行，农历 / 电量退到第二行。
        // 第二行放不下就整行不画，绝不允许两行叠在一起或压到导航栏。
        Store.Day cd = st.clockDay();
        String count = "";
        if (cd != null) {
            count = cd.name + " · " + dayDeltaText(Store.daysFrom(cd.millis));
            count = fit(count, 13, W - 2 * pad, true, false);
        }
        float rowPx = F(13);
        Paint rp = Ink.text(rowPx, false, false);
        ClockStrip.Layout lay = ClockStrip.layout(cy + S(82), F(22), floorY() - S(4),
                rp.descent(), cd != null, !meta.isEmpty());
        clockMetaBase = lay.metaBase;
        if (lay.countBase > 0) {
            CT(c, count, lay.countBase, 13, true, false);
            HIT("clockday", 0, pad, lay.countBase - rowPx, W - pad, lay.countBase + F(6));
        }
        if (lay.metaBase > 0) {
            CT(c, meta, lay.metaBase, 13, false, false);
            HIT("clockmeta", 0, pad, lay.metaBase - rowPx, W - pad, lay.metaBase + F(6));
        }
    }

    // ---------------------------------------------------------------- 番茄钟

    private void drawPomodoro(Canvas c) {
        if (pomoHist) { drawPomoHistory(c); return; }
        String state = pomoRunning ? (pomoPhase == 0 ? "专注中" : "休息中")
                : (pomoLeftMs <= 0 ? "已结束" : "已暂停");
        float headBase = head(c, "番茄钟", state);
        float yRound = headBase + C(24);

        // ---------- 纵向预算：自底向上固定，剩下的全给数字 ----------
        // 旧版把「网格先占位」写在最前、统计只在 gridMode != 0 时画，结果网格一展开
        // 统计就整块消失，网格和按钮之间留一大片白。现在的优先级是：
        //   统计（数据）> 自定义网格（配置）> 数字大小
        // 降级只有一档：整格 → 一行摘要 → 收起，统计始终在。
        float bottom = floorY();
        float bh = btnH();
        float btnTop = bottom - bh;
        // 间距、数字下限、统计标题都属于「界面骨架」，用 chrome 单位（封顶 1.5×）。
        // 以前全用 F：3.1 档时 5 个间距就吃掉 250px、数字下限 222px，
        // 固定块比可用高度还大，统计整块被挤没了
        float gapMin = C(9);
        float roundH = F(11) * 1.6f;
        float segH = MIN(14);                       // 进度条是图形，不跟字号放大
        float toggleH = C(12) * 1.95f;              // 「自定义时长 / 展开」是 chrome 行
        float numMin = C(40);
        float statTitleH = C(24);                   // 「专注统计」标题行
        float statRowH = F(15);                     // 统计里每一行文字
        float statsFull = statTitleH + statRowH * 3;
        float statsMin = statTitleH + statRowH * 2;  // 放不下就压成两行
        float gridH = cfgGridH();

        float avail = btnTop - gapMin - yRound;     // 不含按钮与其上方空隙
        int gridMode = pomoCustom ? 0 : 2;          // 0 整格 / 1 摘要 / 2 收起
        float gridBlock = gridMode == 0 ? gridH : 0f;
        // 整格放不下时，先按缺口收「数字下限」与「间距」——这两项是本页优先级最低的，
        // 与上面「统计 > 网格 > 数字」的约定一致。收不动了才把整张网格降级成一行摘要。
        // 以前直接降级，导航栏一变高（tabH 随字号增大）3.1 档就把网格整个丢了。
        float gapFloor = C(5), numFloor = C(26);
        float over = roundH + segH + toggleH + gridBlock + statsMin
                + gapMin * 5 + numMin - avail;
        if (gridMode == 0 && over > 0f) {
            float gapCut = Math.min(over, Math.max(0f, (gapMin - gapFloor) * 5f));
            gapMin -= gapCut / 5f;
            over -= gapCut;
            float numCut = Math.min(over, Math.max(0f, numMin - numFloor));
            numMin -= numCut;
            over -= numCut;
            if (over > 0.5f) {
                gridMode = 1;
                gridBlock = F(15) + S(6);
            }
        }

        float fixed = roundH + segH + toggleH + gridBlock + statsMin + gapMin * 5 + numMin;
        float slack = Math.max(0f, avail - fixed);
        // 三行统计放得下就用三行（多一行「本年」），放不下压成两行
        boolean statsCompact = slack < statsFull - statsMin;
        float statsH = statsCompact ? statsMin : statsFull;
        slack -= statsH - statsMin;
        // 余量分配：数字先拿一半（最多 +F(56)），其余摊到间距，最后给 7 天分钟格
        float numH = numMin + Math.min(slack * 0.35f, F(40));
        float rest = slack - (numH - numMin);
        // 「本周专注分钟」那 7 个格子比数字再大一点更重要：数字已经够大了，
        // 而这 7 格是实打实的记录，缺了就等于这页没有历史
        float weekH = rest >= S(44) ? Math.min(rest, S(58)) : 0f;
        rest -= weekH;
        float gap = gapMin + Math.min(S(14), rest / 10f);
        rest -= (gap - gapMin) * 5f;
        if (rest > 0f) numH += Math.min(rest, F(20));

        float statsBlock = statsH + (weekH > 0f ? weekH + S(8) : 0f);

        float y = yRound;
        CT(c, "第 " + pomoRound + " 轮 / " + st.pomo.rounds + " · " + phaseName()
                + " · 今日 " + st.pomoDoneToday + " 轮", y, 11, false, false);
        y += roundH + gap;

        // 数字：字号跟着自己的块高走，块被压时数字跟着收，不会掉到进度条上
        float numPx = numH * 0.86f;
        drawMainTime(c, pomoText(), mainPx(pomoText(), numPx), y + numH * 0.80f, W / 2f);
        y += numH + gap;

        // 轮次进度：一段一轮。已完成轮画满，正在专注的那段按时间连续填充；
        // 休息阶段不画当前段，否则一进休息就会看到进度条回退。
        int segs = Math.max(1, Math.min(8, st.pomo.rounds));
        int doneRounds = (pomoPhase == 2) ? pomoRound : Math.max(0, pomoRound - 1);
        float sgw = (W - 2 * pad - (segs - 1) * S(5)) / segs;
        long left = pomoRunning ? (pomoEndAt - SystemClock.elapsedRealtime()) : pomoLeftMs;
        if (left < 0) left = 0;
        long totalMs = Math.max(1, phaseMinutes()) * 60000L;
        float prog = Math.max(0f, Math.min(1f, 1f - left / (float) totalMs));
        for (int i = 0; i < segs; i++) {
            float l = pad + i * (sgw + S(5));
            BOX(c, l, y, l + sgw, y + segH, 1);
            float fill = 0f;
            if (i < doneRounds) fill = 1f;
            else if (i == doneRounds && pomoPhase == 0) fill = prog;
            if (fill > 0f) {
                float w = (sgw - S(8)) * fill;
                if (w > S(1)) Ink.line(c, l + S(4), y + segH / 2f, l + S(4) + w, S(3));
            }
        }
        y += segH + gap;

        TC(c, "自定义时长", pad, y + C(10), 12, true, false);
        // 降级态（gridMode == 1）不能写「收起 -」：那时网格根本没展开，
        // 点它只会切到「展开 +」，再点又因为放不下回到摘要——来回死循环，
        // 用户永远进不了可编辑的网格。所以降级态改成「调整…」，点了弹窗改数值，
        // 保证任何字号 / 朝向都有一条真能改的路径。
        String tog = gridMode == 2 ? "展开 +" : (gridMode == 1 ? "调整…" : "收起 -");
        TC(c, tog, W - pad - CW(tog, 11, false, false), y + C(10), 11, false, false);
        HIT(gridMode == 1 ? "cfgedit" : "cfgtoggle", 0, pad, y, W - pad, y + toggleH);
        y += toggleH;

        if (gridMode == 0) {
            y = drawCfgGrid(c, y);
        } else if (gridMode == 1) {
            // 整格放不下时给一行摘要，值和展开状态下一致
            String sum = st.pomo.focus + " / " + st.pomo.shortBreak + " / "
                    + st.pomo.longBreak + " 分钟 · " + st.pomo.rounds + " 轮";
            T(c, fit(sum, 11, W - 2 * pad, false, true), pad, y + F(11), 11, false, true);
            y += F(15) + S(6);
        }

        // 统计贴底：位置由按钮上沿反推，前面几块怎么缩都不会把它挤掉
        float statsTop = Math.max(y, btnTop - gapMin - statsBlock);
        drawPomoStats(c, statsTop, Math.min(statsBlock, btnTop - statsTop), statsCompact);

        // 三键钉底：内容再多也不许画进导航栏
        y = btnTop;
        String[] labs = {"暂停", "重置", "跳过"};
        float bw = (W - 2 * pad - 2 * S(8)) / 3f;
        for (int i = 0; i < 3; i++) {
            float l = pad + i * (bw + S(8));
            BOX(c, l, y, l + bw, y + bh, 2);
            String lab = (i == 0) ? (pomoRunning ? "暂停" : "继续") : labs[i];
            BTN(c, lab, l, y, bw, bh, 12, true, false);
            if (i == 0 && !pomoRunning) BOX(c, l + S(2), y + S(2), l + bw - S(2), y + bh - S(2), 1);
            HIT("pm", i, l, y, l + bw, y + bh);
        }
    }

    /** 番茄统计：双列文字（今日 / 近 7 天 / 本月 / 本年 / 累计）+ 近 7 天分钟格。 */
    private void drawPomoStats(Canvas c, float y, float availH, boolean compact) {
        // 标题行 + N 行文字。N=3 是「今日/近7天/本月 + 本年/累计」，
        // 放不下时压成 2 行（今日/近7天 + 本月/累计），信息不丢、只是少一行
        int n = compact ? 2 : 3;
        float titleH = C(24), rowH = F(15);
        float y0 = y;
        if (availH < titleH + rowH * n) return;
        T(c, "专注统计", pad, y + C(13), 12, true, false);
        String hist = "历史 ›";
        float hw = TW(hist, 10, false, false);
        T(c, hist, W - pad - hw, y + C(13), 10, false, false);
        HIT("pomohist", 0, W - pad - hw - S(14), y + C(1), W - pad, y + C(22));
        HL(c, pad, W - pad, y + titleH);
        // 第一行文字的基线要落在横线下面：以前直接 y += titleH，
        // 3.1 档下文字顶部会压在那条横线上
        float rowBase = y + titleH + rowH * 0.75f;

        // 双列（左 3 右 2）：五个时间维度压进三行高度，不额外占格子空间。
        // 竖屏只有 420dp 宽，两列各 192dp，字号大一点就会被截断——窄屏用紧凑写法和 9dp
        int[] mins7 = st.pomoLast7MinutesArr();
        boolean near = W < H;
        String sToday = "今日 " + st.pomoDoneToday + (near ? "轮·" : " 轮 · ") + dur(mins7[6]);
        String sWeek = (near ? "近7天 " : "近 7 天 ") + st.pomoLast7Rounds()
                + (near ? "轮·" : " 轮 · ") + dur(st.pomoLast7Minutes());
        String sMonth = "本月 " + st.pomoMonthRounds() + (near ? "轮·" : " 轮 · ")
                + dur(st.pomoMonthMinutes());
        String sYear = "本年 " + st.pomoYearRounds() + (near ? "轮·" : " 轮 · ")
                + dur(st.pomoYearMinutes());
        String sAll = "累计 " + st.pomoAllRounds() + (near ? "轮·" : " 轮 · ")
                + dur(st.pomoAllMinutes());
        String[] left;
        String[] right;
        if (compact) {
            left = new String[]{sToday, sWeek};
            right = new String[]{sMonth, sAll};
        } else {
            left = new String[]{sToday, sWeek, sMonth};
            right = new String[]{sYear, sAll};
        }
        float sd = near ? 9f : 10f;
        float colW = (W - 2 * pad) / 2f;
        for (int i = 0; i < left.length; i++)
            T(c, fit(left[i], sd, colW - S(8), false, true), pad, rowBase + i * rowH, sd, false, true);
        for (int i = 0; i < right.length; i++)
            T(c, fit(right[i], sd, colW - S(8), false, true), pad + colW, rowBase + i * rowH, sd, false, true);
        y = rowBase + (n - 1) * rowH + rowH * 0.25f;

        // 本周 7 格：格内是当天专注分钟数，右下角一个「min」标注单位，
        // 不再在网格下方写注释行——那行会被挤到导航栏上。
        // 剩余高度不够一格就整块不画（大字号 + 矮屏），绝不让格子压到导航栏。
        float space = availH - (y - y0) - S(8);
        if (space < MIN(44)) return;
        float gh = Math.min(MIN(78), space);
        float cw = (W - 2 * pad) / 7f;
        String[] wk = {"一", "二", "三", "四", "五", "六", "日"};
        Calendar cal = Calendar.getInstance();
        int todayIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        float minPx = P(7.5f);
        for (int i = 0; i < 7; i++) {
            float l = pad + i * cw, r = l + cw - S(3), mid = (l + r) / 2f;
            BOX(c, l, y, r, y + gh, 1);
            String d = wk[i];
            // 格内文字用固定像素字号：不跟设置缩放，格高被压缩时也不会顶到星期或压出格底。
            float wpx = P(9);
            TP(c, d, mid - PW(d, wpx, false) / 2f, y + P(12), wpx, false);
            String v = String.valueOf(mins7[i]);
            float px = v.length() >= 3 ? P(12) : P(15);
            float top = y + P(15), bot = y + gh - (gh < S(56) ? P(6) : P(13));
            TP(c, v, mid - PW(v, px, true) / 2f, (top + bot) / 2f + px * 0.36f, px, true);
            // 格子太矮时不写单位，免得「min」贴到数字或压住下边框
            if (gh >= S(56)) TP(c, "min", r - P(4) - PW("min", minPx, false), y + gh - P(6), minPx, false);
            if (i == todayIdx) BOX(c, l + S(1), y + S(1), r - S(1), y + gh - S(1), 1);
        }
    }

    /** 番茄历史列表：一天一行，按日期倒序；顶部可导出（系统分享）或复制。 */
    private void drawPomoHistory(Canvas c) {
        float y = head(c, "番茄历史", "‹ 返回", "histback", 0);
        y += C(26);

        // 右上：复制 / TXT / CSV —— 后两个落成文件，数据多了也能直接进表格
        float bh = btnSmH();
        String[] ops = {"复制", "TXT", "CSV"};
        // 宽度按文字实际宽度算（不用固定 dp）：特大字号下三个按钮才不会把左边挤没
        float bw = S(50);
        for (String op : ops) bw = Math.max(bw, TW(op, 10.5f, true, false) + S(16));
        float[] ox = new float[3];
        float cx = W - pad;
        for (int i = 2; i >= 0; i--) { ox[i] = cx - bw; cx -= bw + S(6); }
        for (int i = 0; i < 3; i++) {
            BOX(c, ox[i], y, ox[i] + bw, y + bh, 2);
            BTN(c, ops[i], ox[i], y, bw, bh, 10.5f, true, false);
            HIT("histexp", i, ox[i], y, ox[i] + bw, y + bh);
        }
        float room = ox[0] - S(10) - pad;
        // 导出只导「当前看的这一段」，这里就把范围写出来，免得误以为又在导好几年的量
        String rn = pomoHistRange == 0 ? "本月" : (pomoHistRange == 1 ? "上月" : "全部");
        String sum = "导出范围 " + rn;
        if (TW(sum, 10, false, true) > room) sum = rn;
        T(c, fit(sum, 10, room, false, true), pad,
                y + bh / 2f + F(10) * 0.36f, 10, false, true);
        y += bh + F(10);

        // 范围筛选：本月 / 上月 / 全部。列表只画当前范围，翻页也只在这个范围里翻。
        String[] rg = {"本月", "上月", "全部"};
        float rh = btnSmH(), rx = pad;
        for (int i = 0; i < 3; i++) {
            float rw = Math.max(S(58), TW(rg[i], 10.5f, false, false) + S(26));
            BOX(c, rx, y, rx + rw, y + rh, 2);
            if (pomoHistRange == i) BOX(c, rx + S(2), y + S(2), rx + rw - S(2), y + rh - S(2), 1);
            BTN(c, rg[i], rx, y, rw, rh, 10.5f, pomoHistRange == i, false);
            HIT("histrange", i, rx, y, rx + rw, y + rh);
            rx += rw + S(6);
        }
        y += rh + F(12);

        // 表头：左边日期，右边「轮数 · 分钟」整体右对齐（两列比三列更紧凑、不空旷）
        T(c, "日期", pad, y + F(10), 9, false, true);
        String hd = "轮数 · 分钟";
        T(c, hd, W - pad - TW(hd, 9, false, true), y + F(10), 9, false, true);
        HL(c, pad, W - pad, y + F(16));
        y += F(24);

        java.util.ArrayList<Store.PomoDay> list = pomoHistList();
        float pageY = H - tabH - S(26);
        float rowH = F(26);
        int per = Math.max(1, (int) ((pageY - S(10) - y) / rowH));
        int pages = Math.max(1, (list.size() + per - 1) / per);
        if (pomoHistPage >= pages) pomoHistPage = 0;
        if (pomoHistPage < 0) pomoHistPage = 0;

        if (list.isEmpty()) {
            T(c, pomoHistRange == 2 ? "还没有记录" : "这个月还没有记录", pad, y + F(22), 11, false, false);
            T(c, "完成一次专注后这里会出现当天记录", pad, y + F(42), 9.5f, false, true);
        }

        String[] wk = {"日", "一", "二", "三", "四", "五", "六"};
        for (int k = 0; k < per; k++) {
            int j = pomoHistPage * per + k;
            if (j >= list.size()) break;
            Store.PomoDay d = list.get(j);
            float ry = y + k * rowH + F(14);
            Calendar cd = Calendar.getInstance();
            cd.set((int) (d.day / 10000L), (int) ((d.day / 100L) % 100L) - 1, (int) (d.day % 100L));
            String ds = String.format("%02d-%02d 周%s", (d.day / 100L) % 100L, d.day % 100L,
                    wk[cd.get(Calendar.DAY_OF_WEEK) - 1]);
            // 只有「全部」范围才需要年份，本月/上月月份足够识别
            if (pomoHistRange == 2) ds = (d.day / 10000L) + "-" + ds;
            T(c, ds, pad, ry, 10.5f, false, false);
            String rv = d.rounds + " 轮 · " + d.minutes + " 分";
            T(c, rv, W - pad - TW(rv, 10.5f, false, true), ry, 10.5f, false, true);
            HL(c, pad, W - pad, ry + F(7));
        }
        // 底部：左边本范围合计，右边翻页
        // 底部只给翻页按钮左边那一小条（S(84)），宽度不够就逐级退：
        // 整行脚注 → 去掉分钟 → 只留天数，绝不顶进页码框
        float fw = S(84) - S(10);
        String foot = list.size() + " 天 · " + sumRange(list);
        if (TW(foot, 9, false, true) > fw) foot = list.size() + " 天 · " + listRounds(list) + " 轮";
        if (TW(foot, 9, false, true) > fw) foot = list.size() + " 天";
        // 本页实际画了几行，翻页行就跟着它走（内容少时不要吊在屏幕最底下）
        int shown = Math.max(0, Math.min(per, list.size() - pomoHistPage * per));
        // 空状态占的是两行说明（F(22) 与 F(42) 的基线），别让「0 天」压在说明上
        float footY = pageRowY(y + (list.isEmpty() ? F(48) : shown * rowH), pages);
        T(c, fit(foot, 9, fw, false, true), pad, footY, 9, false, true);
        listPageBtn(c, pad + S(84), footY, W - 2 * pad - S(84), pages, pomoHistPage, 0, "histpg");
    }

    /** 当前筛选范围下的历史明细（倒序）。 */
    private ArrayList<Store.PomoDay> pomoHistList() {
        ArrayList<Store.PomoDay> all = st.pomoHistoryDesc();
        if (pomoHistRange == 2) return all;
        Calendar c = Calendar.getInstance();
        if (pomoHistRange == 1) c.add(Calendar.MONTH, -1);
        int ym = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
        ArrayList<Store.PomoDay> out = new ArrayList<>();
        for (Store.PomoDay d : all) if ((int) (d.day / 100L) == ym) out.add(d);
        return out;
    }

    private String sumRange(ArrayList<Store.PomoDay> list) {
        int r = 0, m = 0;
        for (Store.PomoDay d : list) { r += d.rounds; m += d.minutes; }
        return r + " 轮 · " + dur(m);
    }

    private int listRounds(ArrayList<Store.PomoDay> list) {
        int r = 0;
        for (Store.PomoDay d : list) r += d.rounds;
        return r;
    }

    /** 步进按钮边长与格子高度：预算和绘制共用一套公式，避免各算各的。 */
    private float cfgStepH() { return Math.max(MIN(22), F(13) + S(7)); }

    private float cfgCellH() {
        // 上下内边距从 S(10) 收到 S(6)：2×2 网格原来在 1.75 档要 355px（屏高 25%），
        // 收紧后 296px，省出来的高度正好给「专注统计」
        return Math.max(MIN(46), F(12) + S(6) + cfgStepH() + S(6));
    }

    /** 配置网格是否排成一行四格（横屏）。竖屏 2×2，横屏 4×1。 */
    private boolean cfgSingleRow() { return W > H; }

    /**
     * 展开网格的实高，用来给番茄页做纵向预算。
     *
     * 横屏必须排成一行：2×2 在横屏上要 341px，比可用高度多出 147px，
     * 会被降级成一行摘要——而横屏的宽度（1448px）放四格绰绰有余。
     * 换句话说，横屏用 2×2 是拿最紧张的方向（高度）去换最富裕的方向（宽度）。
     */
    private float cfgGridH() {
        return (cfgSingleRow() ? 1 : 2) * cfgCellH() + S(7);
    }

    private float drawCfgGrid(Canvas c, float y) {
        String[] labels = {"工作 · 分钟", "短休 · 分钟", "长休 · 分钟", "轮数 · 轮"};
        int[] vals = {st.pomo.focus, st.pomo.shortBreak, st.pomo.longBreak, st.pomo.rounds};
        // 方框高度跟着文字算：写死 S(50)/S(22) 时，大字号下「+ / -」和数值会顶出框
        float sb = cfgStepH();
        float ih = cfgCellH();
        boolean one = cfgSingleRow();
        int cols = one ? 4 : 2;
        float gap = S(7), cw = (W - 2 * pad - gap * (cols - 1)) / cols;
        for (int i = 0; i < 4; i++) {
            int col = i % cols, row = i / cols;
            float l = pad + col * (cw + gap), t = y + row * (ih + gap), r = l + cw, b = t + ih;
            BOX(c, l, t, r, b, 2);
            T(c, labels[i], l + S(11), t + F(12), 9.5f, false, false);

            // 按钮贴底留白 S(7)，不压边框
            float sy = b - S(7) - sb;
            float ml = l + S(11), pl = r - S(11) - sb;
            BOX(c, ml, sy, ml + sb, sy + sb, 2);
            BTN(c, "-", ml, sy, sb, sb, 13, true, true);
            BOX(c, pl, sy, pl + sb, sy + sb, 2);
            BTN(c, "+", pl, sy, sb, sb, 13, true, true);

            // 数值严格居中在左右按钮之间，纵向与按钮齐平
            String v = String.valueOf(vals[i]);
            float vx = (ml + sb + pl) / 2f;
            T(c, v, vx - TW(v, 17, true, true) / 2f, sy + sb / 2f + F(17) * 0.34f, 17, true, true);

            HIT("cfg", i * 2, ml, sy, ml + sb, sy + sb);
            HIT("cfg", i * 2 + 1, pl, sy, pl + sb, sy + sb);
        }
        return y + (one ? 1 : 2) * ih + gap;
    }

    // ---------------------------------------------------------------- 电量统计

    /**
     * 电量统计页：原来是一个带三页的弹窗（点左半边翻页），
     * 改成独立页面后，口径用一排三个按钮切换，和「番茄历史」一样是页内子视图。
     * 文案沿用 battPage()，这里只负责排版：把「使用 xxx」那行放大，其余做明细。
     */
    /**
     * 电量统计页：四个口径（今日 / 上次充电后 / 近 7 天 / 全部）。
     *
     * 版式统一成「一个主数字 + 一张键值表」。以前是把几行字符串拼出来再逐行判断
     * 哪个放大，结果有的字很大、有的很小、还互相挤；现在数值与单位都由
     * BattText 统一生成，页面只负责排版。
     */
    private void drawBatt(Canvas c) {
        float y = head(c, "电量统计", "‹ 返回设置", "battback", 0);
        y += C(20);

        String[] tabs = {"今日", "充电后", "近 7 天", "全部"};
        int nt = tabs.length;
        float pw = (W - 2 * pad - (nt - 1) * S(5)) / nt;
        float ph = btnSmH();
        float sf = 11;
        for (String lab : tabs) {
            while (sf > 8.5f && TW(lab, sf, true, false) > pw - S(6)) sf -= 0.5f;
        }
        for (int i = 0; i < nt; i++) {
            float l = pad + i * (pw + S(5));
            boolean on = battTab == i;
            BOX(c, l, y, l + pw, y + ph, 2);
            if (on) BOX(c, l + S(2), y + S(2), l + pw - S(2), y + ph - S(2), 1);
            BTN(c, tabs[i], l, y, pw, ph, sf, on, false);
            HIT("batttab", i, l, y, l + pw, y + ph);
        }
        y += ph + S(14);

        long ms = battMs(battTab);
        String omitted = "";
        boolean empty;
        if (battTab == 3) {
            empty = st.battFirst <= 0;
        } else if (battTab == 2) {
            empty = true;
            for (Store.DayPoint dp : st.batt7Daily()) {
                if (dp.ms > 0 || dp.msAll > 0) { empty = false; break; }
            }
        } else {
            empty = (battTab == 0 ? st.battToday.pct : st.battCharge.pct) < 0;
        }
        if (empty) {
            T(c, battTab == 3 ? "还没有统计" : "还没有数据", pad, y + F(16), 12, true, false);
            T(c, "开着放几分钟就会开始累计", pad, y + F(38), 10, false, false);
        } else {
            // 主数字：未充电使用时长。数字与单位分两段画，单位不跟着放大
            T(c, battTab == 3 ? "未充电累计" : "未充电使用", pad, y + F(11), 11, false, false);
            String hv = BattText.hero(ms);
            // 注意：T() / TW() 收的是 dp，布局要用像素，所以两个都要算
            float hdp = 26, hpx = F(hdp);
            float hw = TW(hv, hdp, true, true);
            // 数字基线压在标签下方一行：以前用 0.88 倍，数字的顶部直接盖住标签
            float hbase = y + F(11) + hpx;
            T(c, hv, pad, hbase, hdp, true, true);
            T(c, BattText.heroUnit(ms), pad + hw + S(8), hbase - hpx * 0.22f, 12, false, false);
            y += F(11) + hpx + F(14);

            // 键值表：标签左、值右，字号统一，不再有「有的很大有的很小」
            String[][] rows = battRows(battTab);
            float rowH = F(12) * 2.1f;
            // 底部说明要先占位，否则大字号下表格最后一行会压在说明上
            float tipH = F(11) * 2f;
            float tableBottom = floorY() - tipH;
            float pgH = Math.max(MIN(20), F(11) * 1.5f);
            // 放不下就翻页，而不是把行丢掉：以前只写「已省 N 行」，
            // 用户想看那几行就只能去调小字号。
            //
            // 翻页键的位置**必须固定**：这个表每页行数不一样（第一页满、末页可能只剩一行），
            // 键跟着内容走的话，翻页时它会上下跳，第二次点击就点空了。
            // 所以真有多页时才在最下面预留一条翻页行，并把键画死在那条上。
            int perAll = Math.max(1, (int) ((tableBottom - y) / rowH));
            boolean multi = rows.length > perAll;
            float tableEnd = multi ? tableBottom - pgH - F(6) : tableBottom;
            int per = Math.max(1, (int) ((tableEnd - y) / rowH));
            int pages = Math.max(1, (rows.length + per - 1) / per);
            if (battPage >= pages) battPage = 0;
            if (battPage < 0) battPage = 0;
            int from = battPage * per, to = Math.min(rows.length, from + per);
            for (int i = from; i < to; i++) {
                String[] r = rows[i];
                float lw = TW(r[0], 11, false, false);
                float vw = TW(r[1], 11, false, true);
                T(c, r[0], pad, y + F(13), 11, false, false);
                T(c, fit(r[1], 11, W - 2 * pad - lw - S(12), false, true),
                        W - pad - vw, y + F(13), 11, false, true);
                y += rowH;
                HL(c, pad, W - pad, y - F(5));
            }
            // 「近 7 天」的每日柱状图占的是表格下面那块；表格翻页时它只在最后一页出现，
            // 否则第一页的图会把「还有几行没看到」这个信息挤掉
            boolean chartPage = battTab == 2 && battPage == pages - 1;
            if (pages > 1) {
                String pg = "‹ " + (battPage + 1) + "/" + pages + " ›";
                float tw = TW(pg, 9, false, true);
                float bw = Math.max(tw + S(20), S(56));
                float l = W - pad - bw, t = tableBottom - pgH;
                BOX(c, l, t, l + bw, t + pgH, 1);
                T(c, pg, l + (bw - tw) / 2f, t + pgH / 2f + F(9) * 0.34f, 9, false, true);
                HIT("battpg", 0, l, t, l + bw, t + pgH);
            }
            // 近 7 天补一张每日柱状图：只有一张表时页面下半截空着，
            // 而「哪几天用得多」正是这张图要回答的。位置同样固定，
            // 否则最后一页只剩一行字时图会突然长高一截。
            if (battTab == 2 && chartPage) {
                float chartTop = tableEnd + F(10);
                float chartH = Math.min(S(150), tableBottom - chartTop - F(4));
                if (chartH >= MIN(60)) drawBattWeek(c, chartTop, chartH);
                else omitted += " · 图表略";
            }
        }
        // 底部口径说明。降级提示单独右对齐画：拼在长句尾部会被 fit() 二次截断，
        // 数字或「图表略」正好是被吃掉的部分，等于白提示。
        float tipY = floorY() - MIN(4);
        float ow = omitted.isEmpty() ? 0f : TW(omitted, 9, false, false) + S(8);
        String tip = "总时长含充电；折算按未充电。仅统计前台，离开超 100 秒不计时。";
        String left = fit(tip, 9, W - 2 * pad - ow, false, false);
        if (TW(left, 9, false, false) < TW(tip, 9, false, false)) {
            left = fit("折算按未充电；仅统计前台", 9, W - 2 * pad - ow, false, false);
        }
        T(c, left, pad, tipY, 9, false, false);
        if (ow > 0f) T(c, omitted, W - pad - TW(omitted, 9, false, false), tipY, 9, false, false);
    }

    /**
     * 「含充电总时长」的值。必须用与主数字相同的 hero() 精度：
     * 主数字 1h58m 会四舍五入成「2.0 小时」，表格若用 dur() 写「1 小时 58 分」，
     * 看起来会像「总数比未充电还少」。尚未采到充电时段时直接写「同未充电」，
     * 不编造也不制造这种矛盾。
     */
    private String battAllText(int tab, long ms) {
        long all = Math.max(battMsAll(tab), ms);
        if (all <= ms + 30000L) return "同未充电";
        return BattText.hero(all) + " " + BattText.heroUnit(all);
    }

    /** 四个口径各自的「含充电总时长」（毫秒）。 */
    private long battMsAll(int tab) {
        switch (tab) {
            case 0: return st.battToday.msAll;
            case 1: return st.battCharge.msAll;
            case 2: return st.batt7MsAll();
            default: return st.battAllMsAll();
        }
    }

    /** 四个口径各自的累计使用时长（毫秒，**未充电**，折算的分母）。 */
    private long battMs(int tab) {
        switch (tab) {
            case 0: return st.battToday.ms;
            case 1: return st.battCharge.ms;
            case 2: return st.batt7Ms();
            default: return st.battAllMs();
        }
    }

    /**
     * 键值表内容。三个口径给「起算 / 掉电 / 折算 / 上次记录」，
     * 「全部」额外给每小时耗电与每月、每年折算时长。
     * 每个口径都把「含充电总时长」排在前面：它是新加的对照项，
     * 排在末尾会被 3.1 档的表格高度裁掉。折算一律以未充电时长为分母。
     */
    private String[][] battRows(int tab) {
        ArrayList<String[]> out = new ArrayList<>();
        if (tab == 3) {
            int days = st.battAllDays();
            long ms = st.battAllMs();
            int drop = st.battAllDrop();
            out.add(new String[]{"起算日", st.battFirst > 0 ? ymdText(st.battFirst) : "—"});
            out.add(new String[]{"已统计", days + " 天"});
            out.add(new String[]{"含充电总时长", battAllText(tab, ms)});
            out.add(new String[]{"累计掉电", drop > 0 ? drop + "%" : "不足 1%"});
            String rate = BattText.rate(drop, ms);
            out.add(new String[]{"耗电率·未充电", rate.isEmpty() ? "—" : rate + " % / 小时"});
            double m = BattText.estHours(ms, days, 30);
            double y2 = BattText.estHours(ms, days, 365);
            out.add(new String[]{"每月约", BattText.hours(m) + " 小时"});
            out.add(new String[]{"每年约", BattText.hours(y2) + " 小时"});
            return out.toArray(new String[0][]);
        }
        if (tab == 2) {
            long ms = st.batt7Ms();
            int drop = st.batt7Drop();
            out.add(new String[]{"统计范围", "近 7 天"});
            out.add(new String[]{"含充电总时长", battAllText(tab, ms)});
            out.add(new String[]{"累计掉电", drop > 0 ? drop + "%" : "不足 1%"});
            String rate = BattText.rate(drop, ms);
            out.add(new String[]{"折算·未充电", rate.isEmpty() ? "—" : rate + " % / 小时"});
            out.add(new String[]{"日均", BattText.dur(ms / 7L)});
            return out.toArray(new String[0][]);
        }
        long ms = tab == 0 ? st.battToday.ms : st.battCharge.ms;
        int drop = tab == 0 ? st.battToday.drop : st.battCharge.drop;
        int from = tab == 0 ? st.battToday.from : st.battCharge.from;
        int pct = tab == 0 ? st.battToday.pct : st.battCharge.pct;
        long start = tab == 0 ? st.battToday.start : st.battCharge.start;
        long last = tab == 0 ? st.battToday.last : st.battCharge.last;
        boolean charged = tab == 0 ? st.battToday.charged : st.battCharge.charged;
        out.add(new String[]{"起算", start > 0 ? hhmm(start) : "—"});
        out.add(new String[]{"含充电总时长", battAllText(tab, ms)});
        StringBuilder d = new StringBuilder(drop > 0 ? drop + "%" : "不足 1%");
        if (charged) d.append("（期间充过电）");
        else if (drop > 0 && from >= 0 && pct >= 0) d.append("（").append(from).append("% → ").append(pct).append("%）");
        out.add(new String[]{"累计掉电", d.toString()});
        String rate = BattText.rate(drop, ms);
        out.add(new String[]{"折算·未充电", rate.isEmpty() ? "—" : rate + " % / 小时"});
        out.add(new String[]{"上次记录", last > 0 ? hhmm(last) + (pct >= 0 ? " · " + pct + "%" : "") : "—"});
        // 另一个口径的起点也列出来：今日页里能看到「上次充电是几点」，
        // 充电后页里能看到「今天从几点开始用」
        if (tab == 0) {
            out.add(new String[]{"上次充电", st.battCharge.start > 0 ? hhmm(st.battCharge.start) : "—"});
        } else {
            out.add(new String[]{"今日起算", st.battToday.start > 0 ? hhmm(st.battToday.start) : "—"});
        }
        return out.toArray(new String[0][]);
    }

    /** 近 7 天每日使用时长柱状图：柱子高度按当天时长，标签是日期。 */
    private void drawBattWeek(Canvas c, float top, float h) {
        java.util.ArrayList<Store.DayPoint> list = st.batt7Daily();
        if (list.isEmpty()) return;
        T(c, "每日使用", pad, top + F(10), 10, true, false);
        HL(c, pad, W - pad, top + F(16));
        float base = top + h;
        long max = 1;
        for (Store.DayPoint dp : list) max = Math.max(max, dp.ms);
        float cw = (W - 2 * pad) / 7f;
        float barW = cw - S(8);
        // 柱顶要留出写分钟数的位置，否则最高的那根柱子会顶到标题横线上
        float room = h - S(44);
        for (int i = 0; i < list.size() && i < 7; i++) {
            Store.DayPoint dp = list.get(i);
            float l = pad + i * cw + S(4);
            float bh = (float) (dp.ms / (double) max) * room;
            if (bh < S(2)) bh = S(2);
            BOX(c, l, base - bh, l + barW, base, 1);
            // 柱高太矮时数字放不下，只画线；否则写分钟数
            if (bh >= S(16)) {
                String v = String.valueOf(dp.ms / 60000L);
                float vpx = P(8);
                TP(c, v, l + barW / 2f - PW(v, vpx, true) / 2f, base - bh - S(4), vpx, true);
            }
            String ds = String.format(java.util.Locale.US, "%02d-%02d",
                    dp.day / 100 % 100, dp.day % 100);
            TP(c, ds, l + barW / 2f - PW(ds, P(8), false) / 2f, base + P(11), P(8), false);
        }
        HL(c, pad, W - pad, base);
    }

    /** yyyyMMdd → 「2026-11-08」。 */
    private String ymdText(long ymd) {
        return (ymd / 10000) + "-" + String.format(java.util.Locale.US, "%02d",
                ymd / 100 % 100) + "-" + String.format(java.util.Locale.US, "%02d", ymd % 100);
    }

    // ---------------------------------------------------------------- 记录页

    /**
     * 记录页公共框架：页头 + 右上导出三键 + 左侧范围键。
     * 与「番茄历史」同一套形式，两个记录页看起来是一家人。
     * ranges 由调用方给：清单记录能翻上月/全部（Todo 有时间戳），
     * 打卡记录只有本月位图，所以只给「本周 / 本月」两个真实有数据的档。
     */
    private float recFrame(Canvas c, String title, String backTag, String expTag,
                           String rangeTag, String[] ranges, int curRange) {
        float y = head(c, title, "‹ 返回", backTag, 0);
        y += C(20);
        float bh = btnSmH();

        String[] ops = {"复制", "TXT", "CSV"};
        float bw = S(40);
        for (String op : ops) bw = Math.max(bw, TW(op, 10, true, false) + S(10));
        float[] ox = new float[3];
        float cx = W - pad;
        for (int i = 2; i >= 0; i--) { ox[i] = cx - bw; cx -= bw + S(4); }

        float rx = pad;
        float avail = ox[0] - S(6) - pad;
        float gap = S(4);
        // 均分可用宽度，而不是「放不下就砍掉最后几个键」：3.1 档下按钮随字号变宽，
        // 旧逻辑会把清单记录的「全部」直接砍没，用户以为功能丢了。
        float uniform = (avail - gap * (ranges.length - 1)) / Math.max(1, ranges.length);
        for (int i = 0; i < ranges.length; i++) {
            float want = Math.max(S(42), TW(ranges[i], 10, false, false) + S(12));
            float rw = Math.min(want, uniform);
            if (rw < MIN(24)) break;            // 实在窄到放不下才停
            BOX(c, rx, y, rx + rw, y + bh, 2);
            if (curRange == i) BOX(c, rx + S(2), y + S(2), rx + rw - S(2), y + bh - S(2), 1);
            BTN(c, ranges[i], rx, y, rw, bh, 10, curRange == i, false);
            HIT(rangeTag, i, rx, y, rx + rw, y + bh);
            rx += rw + gap;
        }
        for (int i = 0; i < 3; i++) {
            BOX(c, ox[i], y, ox[i] + bw, y + bh, 2);
            BTN(c, ops[i], ox[i], y, bw, bh, 10, true, false);
            HIT(expTag, i, ox[i], y, ox[i] + bw, y + bh);
        }
        return y + bh + F(14);
    }

    /** 待办页右上角的「记录 ›」。 */
    private void todoRecLink(Canvas c, float right, float y) {
        String s = "记录 ›";
        float w = TW(s, 10, false, false);
        T(c, s, right - w, y, 10, false, false);
        HIT("todorec", 0, right - w - S(14), y - F(14), right, y + F(6));
    }

    /**
     * 打卡记录页：按月分组，月内平铺（一行一条，行首写清是哪个习惯）。
     *
     * 早先是「月 → 习惯」两级分组，但习惯只有两三个时，每个习惯一个小标题
     * 把一页切得七零八落，同一天的两次打卡隔着几个标题。现在平铺，
     * 想只看某一个习惯就点上面那排方框键筛（习惯从文字链改成方框，和范围键同款）。
     *
     * 范围：本周 / 本月 / 上月 / 全部。上月与全部依赖按月归档
     * （见 Store.rolloverIfNeeded），归档只从装上这一版之后开始积累。
     */
    private void drawHabitRecords(Canvas c) {
        hrecHabit = HabitScope.clamp(hrecHabit, st.habits.size());
        float y = recFrame(c, "打卡记录", "hrecback", "hrecexp",
                "hrecrange", HREC_RANGES, hrecScope);

        // 习惯分段键：全部 / 早睡 / 早起…（与范围键同款方框，风格统一）
        String[] segs = new String[st.habits.size() + 1];
        segs[0] = "全部";
        for (int i = 0; i < st.habits.size(); i++) segs[i + 1] = st.habits.get(i).name;
        int segCur = hrecHabit == HabitScope.ALL_HABITS ? 0 : hrecHabit + 1;
        y = segmentRow(c, y, segs, segCur, "hrecseg", "hrecmore") + F(10);

        // 习惯名映射：改名后历史跟着新名字走
        HashMap<String, String> names = new HashMap<>();
        for (Store.Habit h : st.habits) names.put(h.id, h.name);

        ArrayList<HabitRec.MonthGrp> months =
                HabitRec.build(st.habitMonths(), hrecScope, Calendar.getInstance(), names);
        // 「全部」档下的月份筛选：只留选中的那个月
        if (hrecScope == HabitScope.ALL && hrecMonth != 0) {
            for (int i = months.size() - 1; i >= 0; i--) {
                if (months.get(i).ym != hrecMonth) months.remove(i);
            }
        }
        String onlyId = hrecHabit == HabitScope.ALL_HABITS ? "" : st.habits.get(hrecHabit).id;
        ArrayList<HabitRec.Flat> flats = HabitRec.flatten(months, onlyId);

        // 摊平成行：月标题 → 明细（日期 | 习惯 | 时刻）
        java.util.Map<Integer, String> titles = RecGroup.titles();
        ArrayList<RecGroup.Row> rows = new ArrayList<>();
        int gid = 0;
        int total = 0;
        for (HabitRec.Flat f : flats) {
            int monthGid = -(++gid);                 // 外层用负数
            titles.put(monthGid, monthTitle(f.ym));
            for (HabitRec.Row r : f.rows) {
                String tm = HabitTime.hhmm(r.mins);
                // 选中单个习惯时不必每行重复它的名字；「全部」档才需要这一列
                rows.add(RecGroup.Row.item(new int[]{monthGid},
                        mdDay(r.ym, r.day), onlyId.isEmpty() ? r.habitName : "",
                        tm.isEmpty() ? "补记" : tm, r.day));
                total++;
            }
        }

        // 信息行：左边条数，右边月份筛选（只在「全部」档有意义）
        float head = y;
        float cx = W - pad;
        if (hrecScope == HabitScope.ALL) {
            if (hrecMonth != 0) {
                cx -= chip(c, cx, head, "不限", false, "hrecmonthclear", 0) + S(6);
            }
            cx -= chip(c, cx, head, hrecMonth == 0 ? "按月 ›" : shortMonth(hrecMonth) + " ›",
                    hrecMonth != 0, "hrecmonth", 0);
        }
        String scope = HabitScope.name(hrecScope);
        T(c, fit(scope + " · " + total + " 条", 10, cx - S(10) - pad, false, true),
                pad, chipRowTextBase(head), 10, false, true);
        y += chipH() + S(6);

        float pageY = H - tabH - S(26);
        float rowH = F(24);
        int per = Math.max(1, (int) ((pageY - S(8) - y) / rowH));
        ArrayList<RecGroup.Page> pages = RecGroup.paginate(rows, titles, per);
        if (hrecPage >= pages.size()) hrecPage = 0;
        if (hrecPage < 0) hrecPage = 0;
        if (total == 0) {
            // 筛了具体月份就说这个月没有，别让用户以为整个历史都空了
            boolean byMonth = hrecMonth != 0;
            T(c, byMonth ? "这个月里没有打卡记录" : "这个范围里还没有打卡记录",
                    pad, y + F(22), 11, false, false);
            T(c, byMonth ? "换个月份，或点上面的「不限」看全部"
                            : (hrecScope == HabitScope.PREV
                            ? "上个月的记录从装上这一版之后才开始积累"
                            : "完成一次打卡后这里会出现当天记录"),
                    pad, y + F(42), 9.5f, false, true);
        }
        // 空状态时内容高度算上那两行说明，否则「0 条」会压在说明文字上
        RecGroup.Page page = pages.get(hrecPage);
        float contentBottom = y + (total == 0 ? F(48) : page.lines.size() * rowH);
        float dcw = TW("00-00", 10.5f, false, true);
        for (int k = 0; k < page.lines.size(); k++) {
            RecGroup.Row r = page.lines.get(k);
            float ry = y + k * rowH;
            if (r.kind == RecGroup.GROUP) {
                // 月标题加重；下划线只画到标题文字末尾——画满全宽会被看成
                // 「下面那条明细的上边框」，层级反而糊了。
                float dp = 11.5f;
                String title = fit(r.title, dp, W - 2 * pad - S(8), true, false);
                T(c, title, pad, ry + F(15), dp, true, false);
                float tw = TW(title, dp, true, false);
                HL(c, pad, Math.min(W - pad, pad + tw + S(6)), ry + F(19));
            } else {
                T(c, r.left, pad, ry + F(15), 10.5f, false, true);
                float vw = TW(r.right, 10.5f, false, true);
                if (!r.note.isEmpty()) {
                    T(c, fit(r.note, 10.5f, W - pad - dcw - S(12) - vw - S(10), false, false),
                            pad + dcw + S(12), ry + F(15), 10.5f, false, false);
                }
                T(c, r.right, W - pad - vw, ry + F(15), 10.5f, false, true);
            }
        }
        float pagerY = pageRowY(contentBottom, pages.size());
        T(c, total + " 条", pad, pagerY, 9, false, true);
        listPageBtn(c, pad + S(70), pagerY,
                W - 2 * pad - S(70), pages.size(), hrecPage, 0, "hrecpg");
    }

    /**
     * 翻页行（条数 + 页码框）该画在哪。
     *
     * 两种情况分开处理：
     * - **只有一页**：跟着内容走（内容结束位置往下留一个行距）。以前固定贴底，
     *   列表只有两三条时页码框孤零零吊在屏幕最下面，看起来像两个不相干的模块。
     * - **多页**：固定在贴底位置。翻页键一旦跟着内容走，翻到最后一页（内容短）
     *   它会往上跳，用户第二次点击就点空了——反复翻页时「不动」比「贴近」重要。
     *
     * @param contentBottom 最后一行的下沿
     * @param pages         总页数
     */
    private float pageRowY(float contentBottom, int pages) {
        float max = H - tabH - S(26);              // 不允许比原来的贴底位置更低
        if (pages > 1) return max;
        return Math.min(max, contentBottom + F(26));
    }

    /**
     * 一排方框分段键（和范围键同款），用于习惯筛选这类「互斥几选一」。
     *
     * 放不下时不硬挤：留下最后一个位置画「更多 ›」，点开弹窗选剩下的——
     * 习惯是用户自己加的，不能假设只有两三个。
     *
     * @param cur  当前选中项下标
     * @param tag  点某一项的 HIT 标签（idx = 该项下标）
     * @param more 溢出时「更多」的 HIT 标签
     * @return 这一行的下沿
     */
    private float segmentRow(Canvas c, float y, String[] labels, int cur, String tag, String more) {
        float bh = btnSmH();
        float gap = S(4);
        float avail = W - 2 * pad;
        float[] w = new float[labels.length];
        float total = gap * Math.max(0, labels.length - 1);
        for (int i = 0; i < labels.length; i++) {
            w[i] = Math.max(S(38), TW(labels[i], 10, false, false) + S(12));
            total += w[i];
        }
        float moreW = TW("更多 ›", 10, false, false) + S(12);
        int n = labels.length;
        if (total > avail) {
            float sum = moreW;
            n = 0;
            while (n < labels.length && sum + w[n] + gap <= avail) {
                sum += w[n] + gap;
                n++;
            }
            if (n < 1) n = 1;                      // 绝不画成空行
        }
        float rx = pad;
        for (int i = 0; i < n; i++) {
            BOX(c, rx, y, rx + w[i], y + bh, 2);
            if (i == cur) BOX(c, rx + S(2), y + S(2), rx + w[i] - S(2), y + bh - S(2), 1);
            BTN(c, labels[i], rx, y, w[i], bh, 10, i == cur, false);
            HIT(tag, i, rx, y, rx + w[i], y + bh);
            rx += w[i] + gap;
        }
        if (n < labels.length) {
            boolean inMore = cur >= n;             // 选中的被折叠进「更多」时也要能看出来
            BOX(c, rx, y, rx + moreW, y + bh, 2);
            if (inMore) BOX(c, rx + S(2), y + S(2), rx + moreW - S(2), y + bh - S(2), 1);
            BTN(c, "更多 ›", rx, y, moreW, bh, 10, inMore, false);
            HIT(more, 0, rx, y, rx + moreW, y + bh);
        }
        return y + bh;
    }

    /**
     * 信息行里的小方框键（月份筛选用）。比次按钮矮一档，能和「N 条」挤在同一行。
     *
     * @param top 上沿
     * @return 宽度
     */
    private float chip(Canvas c, float right, float top, String label, boolean on,
                       String tag, int idx) {
        float h = chipH();
        float w = TW(label, 10, false, false) + S(16);
        float l = right - w;
        BOX(c, l, top, right, top + h, 2);
        if (on) BOX(c, l + S(2), top + S(2), right - S(2), top + h - S(2), 1);
        BTN(c, label, l, top, w, h, 10, on, false);
        HIT(tag, idx, l, top, right, top + h);
        return w;
    }

    /** 信息行里小方框键的高度；同一行左边的文字按它居中。与次按钮同高，一眼是同一套键。 */
    private float chipH() {
        return btnSmH();
    }

    /** 与 chip() 同一行时，文字基线的位置（让字和小方框视觉居中）。 */
    private float chipRowTextBase(float top) {
        return top + chipH() * 0.5f + F(10) * 0.34f;
    }

    /** yyyyMM → 「2026 年 10 月」。 */
    private String monthTitle(int ym) {
        return (ym / 100) + " 年 " + (ym % 100) + " 月";
    }

    /** yyyyMM → 「26-10」：头部筛选按钮上给长标题会让右边挤成一团。 */
    private String shortMonth(int ym) {
        return String.format(java.util.Locale.US, "%02d-%02d", ym / 100 % 100, ym % 100);
    }

    /** yyyyMM + 日 → 「11-05」（用记录所在月份，不是当前月）。 */
    private String mdDay(int ym, int day) {
        return String.format(java.util.Locale.US, "%02d-%02d", ym % 100, day);
    }

    /**
     * 清单记录页：已完成的条目。
     *
     * 「全部」档按月分组（每个月份一个标题），本月/上月档按完成时间倒序平铺——
     * 只有一个月份时再分组反而多一层标题，没有意义。
     *
     * 点任意一条记录进详情页：内容全文、加入时间、完成时间、耗时。
     */
    private void drawTodoRecords(Canvas c) {
        float y = recFrame(c, "清单记录", "trecback", "trecexp", "trecrange",
                REC_RANGES, recRange);
        ArrayList<Store.Todo> list = todoRecordList();

        // 摊平成行
        java.util.Map<Integer, String> titles = RecGroup.titles();
        ArrayList<RecGroup.Row> rows = new ArrayList<>();
        int gid = 0;
        int curMonth = -1;
        for (int i = 0; i < list.size(); i++) {
            Store.Todo t = list.get(i);
            int ym = DayMath.ym(t.doneAt);
            int[] chain;
            if (recRange == 2) {
                // 全部档：按月分组
                if (ym != curMonth) {
                    curMonth = ym;
                    gid++;
                    titles.put(gid, monthTitle(ym));
                }
                chain = new int[]{gid};
            } else {
                chain = new int[0];
            }
            rows.add(RecGroup.Row.item(chain, "", "", i));
        }

        // 信息行：左边条数，右边月份筛选（方框键，和打卡记录页同一套）
        String scope = recRange == 0 ? "本月" : (recRange == 1 ? "上月" : "全部");
        float cx = W - pad;
        if (recRange == 2) {
            if (recMonth != 0) cx -= chip(c, cx, y, "不限", false, "trecmonthclear", 0) + S(6);
            cx -= chip(c, cx, y, recMonth == 0 ? "按月 ›" : shortMonth(recMonth) + " ›",
                    recMonth != 0, "trecmonth", 0);
        }
        T(c, fit(scope + " · " + list.size() + " 条", 10, cx - S(10) - pad, false, true),
                pad, chipRowTextBase(y), 10, false, true);
        y += chipH() + S(6);

        float pageY = H - tabH - S(26);
        float rowH = F(26);
        int per = Math.max(1, (int) ((pageY - S(8) - y) / rowH));
        ArrayList<RecGroup.Page> pages = RecGroup.paginate(rows, titles, per);
        if (recPage >= pages.size()) recPage = 0;
        if (recPage < 0) recPage = 0;
        if (list.isEmpty()) {
            boolean byMonth = recRange == 2 && recMonth != 0;
            T(c, byMonth ? "这个月里没有已完成记录" : "这个范围里没有已完成记录",
                    pad, y + F(22), 11, false, false);
            T(c, byMonth ? "换个月份，或点上面的「不限」看全部" : "完成的待办会自动出现在这里",
                    pad, y + F(42), 9.5f, false, true);
        }
        RecGroup.Page page = pages.get(recPage);
        // 空状态时内容高度要算上那两行说明，否则「0 条」会压在说明文字上
        float contentBottom = y + (list.isEmpty() ? F(48) : page.lines.size() * rowH);
        float dcw = TW("00-00", 10.5f, false, true);
        for (int k = 0; k < page.lines.size(); k++) {
            RecGroup.Row r = page.lines.get(k);
            float ry = y + k * rowH;
            if (r.kind == RecGroup.GROUP) {
                // 下划线只画到标题文字末尾（同打卡记录页）
                String title = fit(r.title, 11.5f, W - 2 * pad - S(8), true, false);
                T(c, title, pad, ry + F(15), 11.5f, true, false);
                float tw = TW(title, 11.5f, true, false);
                HL(c, pad, Math.min(W - pad, pad + tw + S(6)), ry + F(19));
                continue;
            }
            Store.Todo t = list.get(r.payload);
            Calendar tc = Calendar.getInstance();
            tc.setTimeInMillis(t.doneAt);
            String ds = String.format(java.util.Locale.US, "%02d-%02d",
                    tc.get(Calendar.MONTH) + 1, tc.get(Calendar.DAY_OF_MONTH));
            float vw = TW(hhmm(t.doneAt), 10.5f, false, true);
            T(c, ds, pad, ry + F(15), 10.5f, false, true);
            T(c, fit(t.text, 11, W - 2 * pad - dcw - S(12) - vw - S(10), false, false),
                    pad + dcw + S(12), ry + F(15), 11, false, false);
            T(c, hhmm(t.doneAt), W - pad - vw, ry + F(15), 10.5f, false, true);
            // 整行可点：进详情页
            HIT("todorecitem", r.payload, pad, ry + F(2), W - pad, ry + rowH - F(4));
        }
        float pagerY = pageRowY(contentBottom, pages.size());
        T(c, list.size() + " 条", pad, pagerY, 9, false, true);
        listPageBtn(c, pad + S(70), pagerY,
                W - 2 * pad - S(70), pages.size(), recPage, 0, "trecpg");
    }

    /**
     * 清单记录详情页：内容全文 + 加入时间 + 完成时间 + 耗时。
     *
     * 列表里内容被截断、时刻只有时分，这里给全量信息。
     * 「开始时间」用加入时间（待办没有单独的「开始」状态，只有加入与完成）。
     */
    private void drawTodoRecordDetail(Canvas c) {
        ArrayList<Store.Todo> list = todoRecordList();
        // 越界说明记录被删了：直接返回空页，不在绘制期改状态
        if (todoDetail < 0 || todoDetail >= list.size()) return;
        Store.Todo t = list.get(todoDetail);

        float y = head(c, "记录详情", "‹ 返回", "trecdetailback", 0);
        y += C(20);

        // 内容全文：可换行。框高按实际文字行数算，短内容不留一大块空白
        float maxW = W - 2 * pad - S(16);
        int lines = textLines(t.text, maxW, 11.5f);
        float boxH = Math.max(F(30), F(19) * lines + F(10));
        BOX(c, pad, y, W - pad, y + boxH, 1);
        float ty = y + F(20);
        if (t.text.length() > 200) {
            ty = wrap(c, t.text.substring(0, 200), pad + S(8), ty, maxW, 11.5f, false);
            T(c, "…（已截断）", pad + S(8), ty, 9.5f, false, true);
        } else {
            ty = wrap(c, t.text, pad + S(8), ty, maxW, 11.5f, false);
        }
        y += boxH + C(18);

        // 键值表：加入 / 完成 / 耗时
        // 分隔线画在行**下方**而不是压在基线上——原来线正好穿过字，看着像删除线
        long cost = t.doneAt > 0 ? t.doneAt - t.addAt : 0;
        String[][] kv = {
                {"加入", stamp(t.addAt)},
                {"完成", t.doneAt > 0 ? stamp(t.doneAt) : "—"},
                {"耗时", cost > 0 ? durText(cost) : "—"},
        };
        for (String[] r : kv) {
            float lw = TW(r[0], 11, false, false);
            float base = y + F(14);
            T(c, r[0], pad, base, 11, false, false);
            T(c, fit(r[1], 11, W - 2 * pad - lw - S(12), false, true),
                    W - pad - TW(r[1], 11, false, true), base, 11, false, true);
            // 分隔线画在**本行基线下方**一点：字底在 base+字高/2 附近，
            // 线再往下一点就既不压字、又不与下一行粘在一起
            HL(c, pad, W - pad, base + F(8));
            y += F(26);
        }
        y += F(12);
        T(c, "清单条目不保存「开始」动作，最早的时间就是加入时间",
                pad, y + F(12), 9.5f, false, true);
    }

    /** 估算文本在给定宽度下占几行（与 wrap 一致的算法）。 */
    private int textLines(String text, float maxW, float dp) {
        if (text == null || text.isEmpty()) return 1;
        Paint p = Ink.text(F(dp), false, false);
        int n = 1, start = 0;
        while (start < text.length()) {
            int k = p.breakText(text, start, text.length(), true, maxW, null);
            if (k <= 0) k = 1;
            start += k;
            n++;
        }
        return n;
    }

    /** 已完成待办（按当前范围筛选），完成时间倒序。 */
    /**
     * 记录页的数据源：已完成、在范围内、且通过月份筛选的条目，按完成时间倒序。
     *
     * 月份筛选放在这里而不是绘制时过滤：详情页也用这个列表按下标取记录，
     * 两处若用不同口径，点开详情会看到另一条。
     */
    private ArrayList<Store.Todo> todoRecordList() {
        ArrayList<Store.Todo> out = new ArrayList<>();
        for (Store.Todo t : st.todos) {
            if (!t.done || t.doneAt <= 0) continue;
            if (!inRecRange(t.doneAt)) continue;
            if (recRange == 2 && recMonth != 0 && DayMath.ym(t.doneAt) != recMonth) continue;
            out.add(t);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Store.Todo>() {
            @Override
            public int compare(Store.Todo a, Store.Todo b) {
                return a.doneAt < b.doneAt ? 1 : (a.doneAt > b.doneAt ? -1 : 0);
            }
        });
        return out;
    }

    /** 完成时间是否落在当前范围（本月 / 上月 / 全部）。 */
    private boolean inRecRange(long ms) {
        if (recRange == 2) return true;
        Calendar c = Calendar.getInstance();
        if (recRange == 1) c.add(Calendar.MONTH, -1);
        int ym = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        return (t.get(Calendar.YEAR) * 100 + t.get(Calendar.MONTH) + 1) == ym;
    }

    /** 打卡记录导出文本：表头写清范围与习惯，避免导出后不知道筛了什么。 */
    private String habitRecordText() {
        StringBuilder b = new StringBuilder("打卡记录 · ").append(HabitScope.name(hrecScope))
                .append(" · ").append(habitFilterName()).append('\n');
        for (String[] r : habitRecordRows()) {
            b.append(r[0]).append(' ').append(r[1]).append(' ').append(r[2]).append(' ').append(r[3]).append('\n');
        }
        return b.toString();
    }

    private String habitRecordCsv() {
        StringBuilder b = new StringBuilder("月份,日期,习惯,时刻\n");
        for (String[] r : habitRecordRows()) {
            b.append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append('\n');
        }
        return b.toString();
    }

    /**
     * 打卡记录行：{月份, 日期, 习惯名, 时刻}。
     *
     * 与页面用同一套逻辑（HabitRec.build → flatten），且同样尊重当前的
     * 月份 / 习惯筛选——以前导出不看月份筛选，筛着一个月导出却是全部，
     * 导出的内容和屏幕上看到的对不上。
     */
    private ArrayList<String[]> habitRecordRows() {
        ArrayList<String[]> out = new ArrayList<>();
        HashMap<String, String> names = new HashMap<>();
        for (Store.Habit h : st.habits) names.put(h.id, h.name);
        ArrayList<HabitRec.MonthGrp> months =
                HabitRec.build(st.habitMonths(), hrecScope, Calendar.getInstance(), names);
        if (hrecScope == HabitScope.ALL && hrecMonth != 0) {
            for (int i = months.size() - 1; i >= 0; i--) {
                if (months.get(i).ym != hrecMonth) months.remove(i);
            }
        }
        String onlyId = hrecHabit == HabitScope.ALL_HABITS ? "" : st.habits.get(hrecHabit).id;
        for (HabitRec.Flat f : HabitRec.flatten(months, onlyId)) {
            for (HabitRec.Row r : f.rows) {
                String tm = HabitTime.hhmm(r.mins);
                out.add(new String[]{monthTitle(r.ym), mdDay(r.ym, r.day),
                        r.habitName, tm.isEmpty() ? "补记" : tm});
            }
        }
        return out;
    }

    private String habitFilterName() {
        return hrecHabit == HabitScope.ALL_HABITS || hrecHabit >= st.habits.size()
                ? "全部习惯" : st.habits.get(hrecHabit).name;
    }

    /** 清单记录导出文本。 */
    private String todoRecordText() {
        StringBuilder b = new StringBuilder("清单记录\n");
        for (Store.Todo t : todoRecordList()) {
            b.append(dateShort(t.doneAt)).append(' ').append(t.text).append(' ').append(hhmm(t.doneAt)).append('\n');
        }
        return b.toString();
    }

    private String todoRecordCsv() {
        StringBuilder b = new StringBuilder("日期,内容,完成时刻\n");
        for (Store.Todo t : todoRecordList()) {
            b.append(dateShort(t.doneAt)).append(',').append(t.text.replace(",", "，"))
                    .append(',').append(hhmm(t.doneAt)).append('\n');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- ToDo

    /** 按范围与完成状态取出原始索引，保证勾选后自动归入另一区。 */
    /**
     * 待办范围：今天 / 昨天 / 本周 / 全部。
     * 「昨天」和「本周」是按日期区间取，不是「最近改过的」——勾选后条目会跨范围消失，
     * 这正是想要的：今天没做完的事，明天在「昨天」里找得到，本周剩下的在「本周」里看全貌。
     */
    static final String[] TODO_SCOPES = {"今天", "昨天", "本周", "全部"};

    private ArrayList<Integer> todoIdx(boolean doneWanted) {
        ArrayList<Integer> r = new ArrayList<>();
        long today = Store.today(), yest = Store.dayYmd(-1);
        for (int i = 0; i < st.todos.size(); i++) {
            Store.Todo t = st.todos.get(i);
            if (todoScope == 0 && t.day != today) continue;
            if (todoScope == 1 && t.day != yest) continue;
            if (todoScope == 2 && !Store.inThisWeek(t.day)) continue;
            if (t.done != doneWanted) continue;
            r.add(i);
        }
        return r;
    }

    /**
     * 单条清单项：主文字 + 加入时间 / 完成时间，右侧「改 / 删」。
     * compact=true 时省掉时间行（大字号下整页放不下一行的兜底）。
     */
    private void drawTodoCell(Canvas c, float x, float y, float right, Store.Todo t, int idx,
                              boolean compact) {
        float h = todoCellH(t, compact);
        float s = F(11), bt = y - s * 0.8f;
        BOX(c, x, bt, x + s, bt + s, 2);
        if (t.done) Ink.check(c, x, bt, s, S(2));
        // 整行先注册；后面注册的「改 / 删」按钮优先命中
        HIT("todo", idx, x, y - F(15), right, y - F(13) + h);
        float tx = x + s + F(6);
        // 紧凑行下「改/删」两个按钮会吃掉大半格宽，只剩「特…」两个字。
        // 宽度不够时先让位给文字：仍能勾选，改/删调小字号再做。
        float opsW = Math.max(MIN(23), F(10) + S(11)) * 2f + S(4);
        boolean roomForOps = !compact || (right - tx - opsW) >= F(11) * 3.6f;
        float opLeft = roomForOps ? ops(c, y, right, "todoedit", "tododel", idx) : right;
        String txt = fit(t.text, 11.5f, opLeft - S(4) - tx, false, false);
        T(c, txt, tx, y, 11.5f, false, false);
        if (t.done) Ink.line(c, tx, y - F(4), tx + TW(txt, 11.5f, false, false), S(2));

        // 加入 / 完成时间：一行放得下就横排，放不下才分成两行
        String add = "加 " + stamp(t.addAt);
        float mw = opLeft - S(4) - tx;
        if (compact) {
            // 紧凑行不画时间，直接收尾
        } else if (t.done) {
            String fin = "完 " + stamp(t.doneAt);
            if (TW(add + " · " + fin, 8.5f, false, true) <= mw) {
                T(c, add + " · " + fin, tx, y + F(16), 8.5f, false, true);
            } else {
                T(c, add, tx, y + F(16), 8.5f, false, true);
                T(c, fin, tx, y + F(27.5f), 8.5f, false, true);
            }
        } else {
            T(c, add, tx, y + F(16), 8.5f, false, true);
        }
        // 分隔线压在行底：y 是标题基线，行底在 y - F(13) + h
        Ink.line(c, x, y - F(13) + h - S(1), right, S(1));
    }

    private void listPageBtn(Canvas c, float x, float y, float w,
                             int pages, int page, int col, String tag) {
        if (pages <= 1) return;                 // 只有一页：不画页码框，它只会挡住表头
        String s = "‹ " + (page + 1) + "/" + pages + " ›";
        float tw = TW(s, 9, false, true);
        float bw = Math.max(tw + S(20), S(56));
        float l = x + (w - bw) / 2f, t = y - F(11), b = y + F(5);
        BOX(c, l, t, l + bw, b, 1);
        T(c, s, l + (bw - tw) / 2f, y, 9, false, true);
        HIT(tag, col, l, t, l + bw, b);
    }

    /** 单条高度：已完成多一行时间，行高必须一起加，否则会压到下一条的标题。 */
    private float todoCellH(Store.Todo t, boolean compact) {
        if (compact) return Math.max(MIN(26), F(13) * 1.6f);
        // 52 属于「大数字档」不跟随字号，只写 F(52) 时第二个时间行会掉进下一格，
        // 所以按标题行 + 时间行（1 或 2 行）实际高度算一遍，取大者
        return Math.max(t.done ? S(52) : S(40),
                F(13) + (t.done ? F(33.5f) : F(22)));
    }

    private void drawTodoColumn(Canvas c, float x, float y, float w, float bottom,
                                ArrayList<Integer> list, String name, int col) {
        float right = x + w;
        T(c, name, x, y + F(13), 12, true, false);
        String cnt = list.size() + " 项";
        T(c, cnt, right - TW(cnt, 10, false, true), y + F(13), 10, false, true);
        HL(c, x, right, y + F(18));
        float top = y + F(24);
        // 分页按钮抬高到工具条之上，留 S(24) 给它，避免压住底部按钮
        float pageY = Math.max(bottom - S(24), top + F(24));
        float avail = pageY - F(10) - top;

        // 按每一条的真实高度试排，算出本页能放几条；不用固定行高硬除
        int per = 0;
        float used = 0;
        for (int k = 0; k < list.size(); k++) {
            float h = todoCellH(st.todos.get(list.get(k)), false);
            if (used + h > avail) break;
            used += h; per++;
        }
        // 一条整行都放不下时退成「紧凑行」（只留文字，不画时间行）：
        // 3.1 档原先直接显示「放不下，调小字号」，等于这一页什么都没有。
        boolean compact = false;
        if (per <= 0 && !list.isEmpty()) {
            compact = true;
            used = 0;
            for (int k = 0; k < list.size(); k++) {
                float h = todoCellH(st.todos.get(list.get(k)), true);
                if (used + h > avail) break;
                used += h; per++;
            }
            if (per <= 0) compact = false;
        }
        if (per <= 0) {
            // 一条都放不下（大字号 + 矮屏）：宁可不画，也不能把行压到分页按钮上。
            // 空列表本来就没内容，别误报成「放不下」；右列不重复同一句提示（会撞在一起）。
            if (list.isEmpty()) T(c, "暂无", x, top + F(18), 10.5f, false, false);
            else if (col == 0) T(c, "放不下，调小字号", x, top + F(18), 9.5f, false, false);
            listPageBtn(c, x, pageY, w, 1, 0, col, "todopg");
            return;
        }
        int pages = Math.max(1, (list.size() + per - 1) / per);
        if (col == 0) { if (todoOpenPage >= pages) todoOpenPage = 0; }
        else { if (todoDonePage >= pages) todoDonePage = 0; }
        int page = col == 0 ? todoOpenPage : todoDonePage;
        float cy = top;
        for (int k = 0; k < per; k++) {
            int j = page * per + k;
            if (j >= list.size()) break;
            int i = list.get(j);
            Store.Todo t = st.todos.get(i);
            drawTodoCell(c, x, cy + F(13), right, t, i, compact);
            cy += todoCellH(t, compact);
        }
        if (list.isEmpty()) T(c, "暂无", x, top + F(18), 10.5f, false, false);
        listPageBtn(c, x, pageY, w, pages, page, col, "todopg");
    }

    private void drawTodo(Canvas c) {
        ArrayList<Integer> open = todoIdx(false), done = todoIdx(true);
        // 页头右侧给「记录 ›」入口：两列标题上已经有各自条目数，页头不必再重复一遍
        float y = head(c, "待办清单", null);
        todoRecLink(c, W - pad, y);          // 与标题同一基线
        y += C(26);

        // 顶部原来那个「＋ 添加一项…」大框已删除：它占掉 36~200px，是列表能显示几行的瓶颈。
        // 添加入口挪到底部工具条第一个键（用户建议），列表直接从筛选条下面开始。
        int ns = TODO_SCOPES.length;
        // 四格不是两格：可用宽度只有原来的 1/2，字号先按格宽反算，别让「今天」变成「今…」
        float pw = (W - 2 * pad) / ns, ph = btnSmH();
        float sf = 11;
        for (String lab : TODO_SCOPES) {
            while (sf > 8.5f && TW(lab, sf, true, false) > pw - S(10)) sf -= 0.5f;
        }
        for (int i = 0; i < ns; i++) {
            String lab = TODO_SCOPES[i];
            boolean on = todoScope == i;
            float l = pad + i * pw;
            BOX(c, l, y, l + pw, y + ph, 2);
            if (on) BOX(c, l + S(2), y + S(2), l + pw - S(2), y + ph - S(2), 1);
            BTN(c, lab, l, y, pw, ph, sf, on, false);
            HIT("todoscope", i, l, y, l + pw, y + ph);
        }
        y += ph + S(10);

        float gapCol = S(10);
        float colW = (W - 2 * pad - gapCol) / 2f;
        // 底部工具条位置统一由 btnH() 反推，列区底边取它上方，避免压住按钮
        float tb = H - tabH - S(14) - btnH();
        float bottom = tb - S(8);
        float mid = pad + colW + gapCol / 2f;
        c.drawLine(mid, y + F(2), mid, bottom, Ink.stroke(S(1)));
        drawTodoColumn(c, pad, y, colW, bottom, open, "未完成", 0);
        drawTodoColumn(c, pad + colW + gapCol, y, colW, bottom, done, "已完成", 1);

        // 工具条：四键，第一个是添加入口（与「日常打卡」页同样是四键一行）
        String[] tools = {"＋ 添加", "批量录入", "全部完成", "清除已完成"};
        String[] brief = {"＋", "批量", "全完", "清除"};
        int nt = tools.length;
        float bw = (W - 2 * pad - (nt - 1) * S(6)) / nt, bh = btnH();
        for (int i = 0; i < nt; i++) {
            float l = pad + i * (bw + S(6));
            BOX(c, l, tb, l + bw, tb + bh, 2);
            BTN(c, BTNW(tools[i], 10.5f, true, false) <= bw - S(10) ? tools[i] : brief[i],
                    l, tb, bw, bh, 10.5f, true, false);
            HIT("todotool", i, l, tb, l + bw, tb + bh);
        }
    }

    // ---------------------------------------------------------------- 习惯打卡

    private void drawHabits(Canvas c) {
        String mon = (Calendar.getInstance().get(Calendar.MONTH) + 1) + " 月";
        float y = head(c, "日常打卡", mon);
        y += C(26);

        int dim = Store.daysInMonth(), dom = Store.dayOfMonth(), nh = st.habits.size();
        int done = 0, full = 0, best = 0;
        for (int d = 1; d <= dom && d <= dim; d++) {
            int k = 0;
            for (Store.Habit hb : st.habits) if (hb.days[d]) { done++; k++; }
            if (nh > 0 && k == nh) full++;
        }
        for (Store.Habit hb : st.habits) best = Math.max(best, hb.streak);
        int slots = nh * dom;
        int rate = slots > 0 ? done * 100 / slots : 0;

        String stat = "本月 " + done + " 次 · 完成率 " + rate + "% · 全勤 " + full
                + " 天 · 连续 " + best + " 天";
        // 逐级退化：字放大后整行必然放不下，宁可少两项也不要显示成「最长连续 2…」
        if (TW(stat, 10, false, true) > W - 2 * pad)
            stat = "本月 " + done + " 次 · 完成率 " + rate + "% · 连续 " + best + " 天";
        if (TW(stat, 10, false, true) > W - 2 * pad)
            stat = "本月 " + done + " 次 · 连续 " + best + " 天";
        if (TW(stat, 10, false, true) > W - 2 * pad)
            stat = "本月 " + done + " 次";
        T(c, fit(stat, 10, W - 2 * pad, false, true), pad, y + F(12), 10, false, true);
        HL(c, pad, W - pad, y + F(18));
        y += F(30);

        // 按钮条高度随字号变化，内容区底边必须由它反推，
        // 否则大字号下按钮长高、会顶到月历和提示行上（横屏尤其明显）
        float toolBH = btnH();
        float ibTool = H - tabH - S(6) - toolBH;

        // 先排出习惯选择框，再算「最近记录」要占多高——顺序不能反：
        // 选择框自己有多高由字号决定，先扣记录块会把月历区算成负数，
        // 结果标题和记录叠在一起（3.1 档第一版就是这样）。
        float ib = ibTool;
        float gy = y;
        if (nh > 0) {
            if (gridHabit < 0 || gridHabit >= nh) gridHabit = 0;
            y = drawHabitPicker(c, y);
            gy = y + S(6);
            // 月历放不下（大字号 / 习惯多）时改成本月汇总，信息不丢、也不留空白
            if (monthGridFits(gy, ib)) drawMonthGrid(c, gy, ib);
            else drawHabitSummary(c, gy, ib - S(4));
        } else {
            T(c, "还没有习惯", pad, y + F(16), 11, false, false);
            T(c, "用下面的按钮添加一项，之后每天点一下即可打卡", pad, y + F(36), 10, false, false);
        }

        int nt = nh == 0 ? 2 : 4;
        String[] tools = {"＋ 添加", "批量", "改当前", "删当前"};
        String[] brief = {"＋", "批量", "改", "删"};
        float bw2 = (W - 2 * pad - (nt - 1) * S(6)) / nt;
        float bh = toolBH;                              // 按钮跟着字长高，字不会掉出框
        for (int i = 0; i < nt; i++) {
            float l = pad + i * (bw2 + S(6));
            // 按钮固定在 ibTool（导航栏上方），不能让出「最近记录」那块高度，
            // 否则按钮会压到记录列表上——第一版就是这么错的
            BOX(c, l, ibTool, l + bw2, ibTool + bh, 2);
            BTN(c, BTNW(tools[i], 10.5f, true, false) <= bw2 - S(10) ? tools[i] : brief[i],
                    l, ibTool, bw2, bh, 10.5f, true, false);
            HIT("habittool", i, l, ibTool, l + bw2, ibTool + bh);
        }
    }

    /**
     * 所有习惯收进同一个方框：按顺序一个名字 + 一个状态点，最多两行、每行最多 8 个。
     *
     * 交互刻意做成两步：第一次点只把该习惯设成「当前」，不动任何数据；
     * 再点一次同一项才真正打卡 / 取消。这样在切换卡片（或在补记态看别的习惯）时
     * 手一碰不会就把当天状态改掉。选中项底边加一条粗线，墨水屏上一眼能看出来。
     */
    private float drawHabitPicker(Canvas c, float y) {
        int n = st.habits.size(), dom = Store.dayOfMonth();
        float left = pad + S(10), right = W - pad - S(10), gap = S(6);
        float[] xs = new float[n], ws = new float[n];
        int[] ln = new int[n];
        float cx = left;
        int line = 0, vis = 0, inLine = 0;
        for (int i = 0; i < n; i++) {
            float w = chipWidth(i);
            if (inLine >= 8 || (cx + w > right && cx > left)) {
                line++; cx = left; inLine = 0;
            }
            if (line > 1) break;
            xs[i] = cx; ws[i] = w; ln[i] = line;
            cx += w + gap; vis = i + 1; inLine++;
        }
        int lines = ln[Math.max(0, vis - 1)] + 1;

        float chipH = Math.max(MIN(26), F(10.5f) * 1.9f);
        float headY = y + S(9) + F(11);
        float chipTop = y + S(9) + F(16) + S(8);
        float boxBot = chipTop + lines * chipH + (lines - 1) * S(5) + S(7);
        BOX(c, pad, y, W - pad, boxBot, 2);

        int on = 0;
        for (int i = 0; i < n; i++) if (st.habits.get(i).days[dom]) on++;
        String head = "今日打卡 " + on + "/" + n;
        String more = n > vis ? "全部 " + n + " 项 ›" : "选择 ›";
        float mw = TW(more, 9, false, false);
        // 两步确认的提示：位置不够就整段不画，绝不压缩成「今日打…」
        float room = right - mw - S(12) - left;
        String hint = "· 再点一次打卡";
        if (TW(head, 9.5f, false, true) + TW(hint, 9, false, false) <= room) {
            T(c, head, left, headY, 9.5f, false, true);
            T(c, hint, left + TW(head, 9.5f, false, true) + S(3), headY, 9, false, false);
        } else {
            T(c, fit(head, 9.5f, room, false, true), left, headY, 9.5f, false, true);
        }
        T(c, more, right - mw, headY, 9, false, false);
        // 只有右上角「选择 ›」可点，且命中区只盖住文字本身，别把标题行也算进去
        HIT("habitpick", 0, right - mw - S(10), y + S(2), right + S(6), chipTop - S(2));

        for (int i = 0; i < vis; i++) {
            Store.Habit hb = st.habits.get(i);
            float t = chipTop + ln[i] * (chipH + S(5));
            boolean sel = i == gridHabit, done = hb.days[dom];
            BOX(c, xs[i], t, xs[i] + ws[i], t + chipH, sel ? 2 : 1);
            // 选中标记：底边一条粗线（大字号下 1px 加粗在墨水屏上看不出来）
            if (sel) Ink.line(c, xs[i] + S(4), t + chipH, xs[i] + ws[i] - S(4), Math.max(2f, S(2)));
            String s = fit(hb.name, 10.5f, ws[i] - S(32), false, false);
            T(c, s, xs[i] + S(8), t + chipH / 2f + F(4), 10.5f, sel, false);
            // 状态点跟着胶囊长高，下限 3.6dp：以前固定 3.4dp，6 寸屏上细得看不清
            float dr = Math.max(S(3.6f), Math.min(S(5.4f), chipH * 0.18f));
            Ink.dot(c, xs[i] + ws[i] - S(12), t + chipH / 2f, dr, done);
            HIT("habit", i, xs[i], t, xs[i] + ws[i], t + chipH);
        }
        return boxBot;
    }

    /** 单个胶囊宽度：名字（最多 7 字）+ 右侧状态点 + 两侧内边距。 */
    private float chipWidth(int i) {
        String s = fit(st.habits.get(i).name, 10.5f, S(82), false, false);
        return TW(s, 10.5f, false, false) + S(8) + S(26);
    }

    /**
     * 全习惯月历：一格一天，格内每个习惯一个固定槽位（顺序同习惯列表）。
     * 实心 = 当天已打卡，空心圈 = 未打卡；槽位固定，取消打卡只换样式、不位移。
     * 默认只读（防止误触改历史数据），点标题行「补记」后才可点日期。
     */
    /** 月历是否放得下：与 drawMonthGrid 用同一套公式，避免判断与实际绘制打架。 */
    private boolean monthGridFits(float y, float ib) {
        if (st.habits.isEmpty()) return false;
        float cw = (W - 2 * pad) / 7f;
        float wpx = Math.min(S(9), cw * 0.34f);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int rows = (lead + Store.daysInMonth() + 6) / 7;
        float gh = Math.min(S(84), (ib - S(6) - (y + F(30) + wpx * 1.55f)) / rows);
        return gh >= S(18);
    }

    /** 月历的替代版：每个习惯一行「本月 N 天 · 连续 M 天」。 */
    private void drawHabitSummary(Canvas c, float top, float bottom) {
        int dom = Store.dayOfMonth(), dim = Store.daysInMonth();
        T(c, "本月记录", pad, top + F(14), 12, true, false);
        recLink(c, W - pad, top + F(13));
        HL(c, pad, W - pad, top + F(20));
        float y = top + F(26);
        float rowH = F(30);
        int shown = 0;
        for (int i = 0; i < st.habits.size(); i++) {
            // 最后一行留出提示位置：习惯多时不要静默截断，要告诉用户还有多少
            if (y + rowH > bottom - F(18)) break;
            Store.Habit hb = st.habits.get(i);
            int cnt = 0;
            for (int d = 1; d <= Math.min(dom, dim); d++) if (hb.days[d]) cnt++;
            String v = "本月 " + cnt + " 天 · 连续 " + hb.streak + " 天";
            float vw = TW(v, 10, false, true);
            T(c, fit(hb.name, 12, W - 2 * pad - vw - S(12), true, false),
                    pad, y + F(14), 12, true, false);
            T(c, v, W - pad - vw, y + F(14), 10, false, true);
            Ink.line(c, pad, y + rowH - S(1), W - pad, S(1));
            y += rowH;
            shown++;
        }
        if (shown < st.habits.size()) {
            T(c, "其余 " + (st.habits.size() - shown) + " 项见记录页 ›",
                    pad, y + F(12), 9.5f, false, true);
        }
    }

    /** 右上角「记录 ›」入口：点进记录页。 */
    private void recLink(Canvas c, float right, float y) {
        String s = "记录 ›";
        float w = TW(s, 10, false, false);
        T(c, s, right - w, y, 10, false, false);
        HIT("habitrec", 0, right - w - S(14), y - F(14), right, y + F(6));
    }

    private void drawMonthGrid(Canvas c, float y, float ib) {
        if (st.habits.isEmpty()) return;
        if (gridHabit < 0 || gridHabit >= st.habits.size()) gridHabit = 0;
        Store.Habit hb = st.habits.get(gridHabit);
        int dim = Store.daysInMonth(), dom = Store.dayOfMonth();
        int dm = 0;
        for (int d = 1; d <= dim; d++) if (hb.days[d]) dm++;

        // 标题行右侧：已打卡天数 +「补记」开关（默认只读，开着才能点日期）
        String btn = habitEdit ? "完成" : "补记";
        // 按钮高度跟随字号，底边贴住标题下的横线，放大字号也不会压到上一行
        float bw = Math.max(MIN(48), F(30)), bh = Math.max(MIN(20), F(19));
        float bl = W - pad - bw, bt = y + F(20) - bh - S(1);
        BOX(c, bl, bt, bl + bw, bt + bh, habitEdit ? 2 : 1);
        T(c, btn, bl + bw / 2f - TW(btn, 10, habitEdit, false) / 2f,
                bt + F(15), 10, habitEdit, false);
        HIT("hbedit", 0, bl, bt, bl + bw, bt + bh);

        // 标题：浏览态固定写「本月记录」——之前写的是当前选中的习惯名，
        // 点胶囊切一下标题就变，看着像数据被改了。只有进「补记」才点名在改哪一项。
        String rs = habitEdit ? (dm + "/" + dim + " 天") : (st.habits.size() + " 项");
        float rsx = bl - S(10) - TW(rs, 9.5f, false, true);
        String head = habitEdit
                ? "补记 · " + fit(hb.name, 12, rsx - S(8) - pad, true, false)
                : "本月记录";
        T(c, head, pad, y + F(14), 12, true, false);
        if (habitEdit) {
            T(c, rs, rsx, y + F(13), 9.5f, false, true);
        } else {
            // 浏览态右侧给记录页入口：月历只显示当前习惯，看全部记录要另开一页
            recLink(c, bl - S(10), y + F(13));
        }
        HL(c, pad, W - pad, y + F(20));
        y += F(30);

        // 星期行缩小：它只是列标题，用固定像素画（不跟字号），占一行高就够，
        // 省下来的高度全部让给日期格
        String[] wk = {"一", "二", "三", "四", "五", "六", "日"};
        float cw = (W - 2 * pad) / 7f;
        float wpx = Math.min(S(9), cw * 0.34f);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // 周一为第 0 列
        int rows = (lead + dim + 6) / 7;
        // 格高把内容区的剩余空间全部吃掉（只留 84dp 上限防止少数几行时过分空旷）。
        // 之前卡在 66dp，竖屏 5~6 行月历底部留一大片空白，格子反而挤得放不下日期和点。
        // 星期行要等格高算完再画：格子放不下时连它一起省掉，
        // 否则只留一行「一二三四五六日」吊在那里，看着像坏了
        float calTop = y + wpx * 1.55f;
        float gh = Math.min(S(84), (ib - S(6) - calTop) / rows);
        if (gh < S(18)) return;
        for (int i = 0; i < 7; i++) {
            TP(c, wk[i], pad + i * cw + cw / 2f - PW(wk[i], wpx, false) / 2f,
                    y + wpx, wpx, false);
        }
        y = calTop;

        // 版式按「最宽日期（88）」统一定一次：否则同一个月份里个位数格是一行、
        // 十位数格是两行，看着像排坏了。
        int nh = st.habits.size();
        int count = habitEdit ? 1 : nh;     // 补记态只画当前这一项，点了哪儿一目了然
        float dpx = Math.min(F(10), Math.min(gh * 0.44f, cw * 0.30f));
        float innerL = pad + S(3.5f), innerR = pad + cw - S(2.5f);
        float refW = PW("88", dpx, true);
        float sideW = innerR - (innerL + refW + S(3));
        // 一行排得开就一行（日期 + 点同一条中线）；挤到点径小于 2.6dp
        // （实心空心分不清）才换成「日期在上、点在下」的两行排法
        float dr1 = Math.min(S(6.4f), Math.min(sideW / Math.max(1, count) * 0.32f,
                (gh - S(6)) * 0.32f));
        boolean oneLine = count <= 1 || dr1 >= S(2.6f);

        for (int d = 1; d <= dim; d++) {
            int pos = lead + d - 1, r = pos / 7, col = pos % 7;
            float l = pad + col * cw, t = y + r * gh, rr = l + cw - S(2.5f), bb = t + gh - S(2.5f);
            BOX(c, l, t, rr, bb, 1);

            String ds = String.valueOf(d);
            boolean selfDone = hb.days[d];                 // 当前习惯这天完成了吗
            // 每个习惯固定占一个槽位，顺序 = 习惯顺序（左→右、上→下）。
            // 打卡 = 实心点，没打卡 = 空心圈：取消打卡只是实心变空心，位置不动、不会消失。
            float cy = (t + bb) / 2f;
            float dateW = PW(ds, dpx, true);
            float cl = l + S(3.5f), cr = rr - S(2.5f);
            float sideL = cl + dateW + S(3);

            if (d > dom || count <= 0) {
                TP(c, ds, cl, cy + dpx * 0.36f, dpx, selfDone);   // 未来的日子只画日期
            } else if (oneLine) {
                // 放得下就一行：日期和点同一条中线（格高再矮，点也不会被压到半格里去）
                TP(c, ds, cl, cy + dpx * 0.36f, dpx, selfDone);
                cellDots(c, d, sideL, cr - sideL, t + S(2), bb - S(2), 1,
                        count, selfDone, habitEdit);
            } else {
                // 习惯多 / 大字号放不进一行：日期在上、点用整格宽度往下排一到两行，
                // 整块（日期 + 点）在格子里垂直居中，不会全挤在格顶
                float dateH = dpx * 1.15f;
                float h2 = Math.max(0, (bb - t) - dateH - S(2));
                int nrows = Math.max(1, Math.min(2, (int) (h2 / S(9))));
                float rowH = Math.min(h2 / nrows, S(13));
                float blockH = dateH + S(2) + rowH * nrows;
                float t0 = t + Math.max(0, (gh - blockH) / 2f);
                TP(c, ds, cl, t0 + dateH, dpx, selfDone);
                cellDots(c, d, l + S(2.5f), cr - (l + S(2.5f)),
                        t0 + dateH + S(2), t0 + blockH, nrows, count, selfDone, habitEdit);
            }
            if (d == dom) BOX(c, l + S(1), t + S(1), rr - S(1), bb - S(1), 1);
            if (habitEdit) HIT("gridcell", d, l, t, rr, bb);
        }
        y += rows * gh;
        // 图例不再单独占一行：横屏 + 大字号时那一行必然压在按钮条上。
        // 「补记中」的状态已经由标题和按钮文字表达，这里只保留必要信息在标题行。
    }

    /**
     * 月历格里的状态点阵列：给定区域按 rows 行 × cols 列铺满，点径取能放下的最大值。
     * 只有一列行时整块垂直居中，正好和左边的日期落在同一条中线上。
     */
    private void cellDots(Canvas c, int d, float left, float width, float top, float bottom,
                          int nrows, int count, boolean selfDone, boolean onlyCurrent) {
        if (count <= 0 || nrows <= 0 || width <= S(3) || bottom - top <= S(3)) return;
        int cols = (int) Math.ceil(count / (float) nrows);
        float stepX = width / cols;
        float rowH = (bottom - top) / nrows;
        float dr = Math.min(S(6.4f), Math.min(stepX * 0.32f, rowH * 0.32f));
        dr = Math.max(S(1.8f), dr);
        for (int i = 0; i < count; i++) {
            int r = i / cols, q = i % cols;
            boolean on = onlyCurrent ? selfDone : st.habits.get(i).days[d];
            Ink.dot(c, left + stepX * (q + 0.5f), top + rowH * (r + 0.5f), dr, on);
        }
    }

    // ---------------------------------------------------------------- 倒数日

    /** 事件名前缀：把「置顶到台历」「在时钟页显示」两个开关标出来，免得点开菜单才想起选了哪条。 */
    private String dayTag(Store.Day d) {
        if (d.pinned && d.onClock) return "顶·钟 · ";
        if (d.pinned) return "顶 · ";
        if (d.onClock) return "钟 · ";
        return "";
    }

    private void drawDays(Canvas c) {
        String cnt = st.days.size() + " 项";
        float y = head(c, "倒数日", cnt);
        y += C(20);

        float ib = floorY() - btnH() - S(12);
        // 只有「置顶」才做成大卡。以前 primaryDay() 在没有置顶时返回第一条，
        // 于是不管置不置顶第一个都变大——现在严格只看 pinnedDay()。
        Store.Day pin = st.pinnedDay();
        if (pin != null) {
            long n = Store.daysFrom(pin.millis);
            // 卡片高度：上一版 F(104)（1.75 档 327px、占屏高 23%）还是太大，
            // 收到 F(54)（约 170px、12%），内部一行标题 + 一行数字。
            float cardH = Math.min(F(54), ib - y);
            cardH = Math.max(Math.min(MIN(60), ib - y), cardH);
            float b = y + cardH;
            BOX(c, pad, y, W - pad, b, 2);
            String ds = dateShort(pin.millis);
            float dsw = TW(ds, 9.5f, false, true);
            float tl = pad + S(12), tr = W - pad - S(12);
            T(c, ds, tr - dsw, y + F(13), 9.5f, false, true);
            T(c, fit(dayTag(pin) + pin.name, 12, tr - dsw - S(10) - tl, true, false),
                    tl, y + F(13), 12, true, false);
            // 数字占卡片下半部，字高按内区反算；右边跟「天 / 天前」
            float numTop = y + F(20), numBottom = b - S(6);
            String big = String.valueOf(Math.abs(n));
            float px = Math.min((numBottom - numTop) * 0.86f, F(38));
            float availW = W - 2 * pad - S(24);
            while (px > S(14)
                    && PWx(big, px, true, true) + S(8) + px * 0.34f > availW) px *= 0.94f;
            TPx(c, big, tl, numBottom, px, true, true);
            float upx = Math.max(F(11), px * 0.3f);
            TPx(c, n >= 0 ? "天" : "天前", tl + PWx(big, px, true, true) + S(8),
                    numBottom, upx, false, false);
            HIT("dayedit", st.days.indexOf(pin), pad, y, W - pad, b);
            y = b + S(10);
        }

        // 其余条目：普通行 + 边框（以前只有文字，像散落的标签）
        float rowH = Math.max(MIN(30), F(13) * 1.9f);
        for (int i = 0; i < st.days.size(); i++) {
            Store.Day d = st.days.get(i);
            if (d == pin) continue;
            if (y + rowH > ib) break;
            long n = Store.daysFrom(d.millis);
            float rt = y, rb = y + rowH;
            BOX(c, pad, rt, W - pad, rb, 1);
            float ry = rb - rowH * 0.32f;
            float right = ops(c, ry, W - pad - S(8), "dayedit", "daydel", i);
            String v = dayDeltaText(n);
            float vx = right - S(8) - TW(v, 10.5f, false, true);
            T(c, fit(dayTag(d) + d.name, 12, vx - S(10) - (pad + S(10)), true, false),
                    pad + S(10), ry, 12, true, false);
            T(c, v, vx, ry, 10.5f, false, true);
            HIT("dayedit", i, pad, rt, right, rb);
            y = rb + S(6);
        }
        if (st.days.isEmpty()) {
            T(c, "还没有事件", pad, y + F(14), 11, false, false);
            T(c, "用下面的按钮添加一个", pad, y + F(32), 9.5f, false, true);
        }

        String[] tools = {"＋ 添加事件", "批量录入"};
        String[] brief = {"＋ 事件", "批量"};
        float bw = (W - 2 * pad - S(6)) / 2f;
        float bh2 = btnH();
        // 按钮钉在导航栏上方：位置固定，翻到最后一页也不会跳来跳去
        float bY = ib;
        for (int i = 0; i < 2; i++) {
            float l = pad + i * (bw + S(6));
            BOX(c, l, bY, l + bw, bY + bh2, 2);
            BTN(c, BTNW(tools[i], 11, true, false) <= bw - S(10) ? tools[i] : brief[i],
                    l, bY, bw, bh2, 11, true, false);
            HIT("daytool", i, l, bY, l + bw, bY + bh2);
        }
    }

    // ---------------------------------------------------------------- 设置 / 建议

    /**
     * 7 档字号：1.3 起步（标准档），最高 3.1。
     * 早先最小档是 0.9，实际用不上——墨水屏离眼远，0.9 比系统默认还小；
     * 而大字号那一端又不够用，所以整条曲线整体上移，并按用户要求拆成更多档。
     */
    static final float[] FONT_STEPS = {1.3f, 1.5f, 1.75f, 2f, 2.3f, 2.7f, 3.1f};
    static final float FONT_MIN = FONT_STEPS[0], FONT_MAX = FONT_STEPS[FONT_STEPS.length - 1];

    /** 当前字号落在第几档（导入的旧值也会先钳到区间内）。 */
    int fontStep() {
        int at = 0;
        float best = 9f;
        for (int i = 0; i < FONT_STEPS.length; i++) {
            float dd = Math.abs(FONT_STEPS[i] - st.fontScale);
            if (dd < best) { best = dd; at = i; }
        }
        return at;
    }

    private String fontScaleText() {
        return String.valueOf(Math.round(st.fontScale * 100) / 100f);
    }

    private void drawSettings(Canvas c) {
        String ver = act.versionName();
        float y = head(c, "设置", ver.isEmpty() ? "" : "v" + ver);
        y += C(26);

        // 「番茄钟时长」不再单列一行：番茄页自己就能改（网格或摘要态的「调整…」弹窗），
        // 这里再放一个跳转入口只是让人多跳一次。
        String[] keys = {"显示秒", "时间制", "农历", "保持常亮", "OLED 模式", "字号",
                "电量统计", "墨水屏使用建议", "导出配置", "导入配置", "清空数据"};
        String[] vals = {
                st.showSeconds ? "开" : "关",
                st.h24 ? "24 小时" : "12 小时",
                st.lunar ? "开" : "关",
                st.keepOn ? "开" : "关",
                st.oled ? "黑底白字" : "关",
                fontScaleText() + "　" + (fontStep() + 1) + "/" + FONT_STEPS.length,
                battText(),
                "›", "JSON ›", "JSON ›", "›"
        };
        // 行距自适应：行数 × F(38) 在大字号竖屏会整体溢出到 tab 栏，
        // 这里按剩余高度算行距（下限略高于字高，绝不叠行）
        // 行数是硬约束：行高 = 可用高度 / 行数，字号跟着行高收。
        // 以前下限写 S(20)（随缩放变），3.1 档下这个下限本身就超过「可用高度 / 行数」，
        // 结果最后三行被顶到导航栏下面直接看不见。
        float perRow = Math.min(F(38), (floorY() - y) / keys.length);
        float tsPx = Math.min(F(13), perRow * 0.58f);
        float vsPx = Math.min(F(12), tsPx);
        for (int i = 0; i < keys.length; i++) {
            float ry = y + perRow * 0.62f;
            boolean on = vals[i].equals("开");
            // 右侧值先按常规排；挤不下就降到 10 号，再挤不下才截断。
            float maxV = W - 2 * pad - PWx(keys[i], tsPx, false, false) - S(10);
            String v = vals[i];
            float vs = vsPx;
            if (PWx(v, vs, on, true) > maxV) vs = Math.min(vsPx, F(10));
            if (PWx(v, vs, on, true) > maxV) v = fitP(v, vs, maxV, on, true);
            float vw = PWx(v, vs, on, true);
            TPx(c, keys[i], pad, ry, tsPx, false, false);
            TPx(c, v, W - pad - vw, ry, vs, on, true);
            if (on) {
                float vx = W - pad - vw;
                Ink.line(c, vx, ry + tsPx * 0.3f, W - pad, S(2));
            }
            HIT("set", i, pad, ry - perRow * 0.55f, W - pad, ry + perRow * 0.32f);
            y += perRow;
        }

    }

    // ---------------------------------------------------------------- 电量明细

    private static String hhmm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return ClockText.two(c.get(Calendar.HOUR_OF_DAY)) + ":"
                + ClockText.two(c.get(Calendar.MINUTE));
    }

    private static String mdhm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return ClockText.two(c.get(Calendar.MONTH) + 1) + "-"
                + ClockText.two(c.get(Calendar.DAY_OF_MONTH)) + " " + hhmm(ms);
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
                && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    /** 区间文字：同天只写时间，隔天用「昨」，再久写日期；宽度都控制在 11~12 字。 */
    private static String rangeText(long a, long b) {
        if (sameDay(a, b)) return hhmm(a) + "–" + hhmm(b);
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        if (y.get(Calendar.YEAR) == x.get(Calendar.YEAR)
                && y.get(Calendar.DAY_OF_YEAR) - x.get(Calendar.DAY_OF_YEAR) == 1) {
            return "昨" + hhmm(a) + "–" + hhmm(b);
        }
        return mdhm(a);
    }


    private void drawTips(Canvas c) {
        float y = head(c, "使用建议", "‹ 返回设置");
        // 整行都能返回：墨水屏上指哪儿打哪儿，别只让用户瞄准那几个字
        HIT("tab", SET, pad, y - F(17), W - pad, y + F(8));
        y += C(26);
        String[] lines = {
                "1. 在系统设置里为本应用选择「普通 / 均衡刷新」，不要用极速模式，残影更少。",
                "2. 开启系统「自动清除残影 / 定期全刷」。应用不再自行插入全刷，避免重复闪烁。",
                "3. 默认精度为「时:分」，每分钟只重绘变化的数字位。打开秒后刷新次数约增加 60 倍，更耗电、更容易留残影。",
                "4. 界面只用黑与白，状态用线框、勾与删除线表达，没有实心色块与反白。",
                "5. 保持常亮只影响系统休眠，不会增加墨水屏耗电：静态画面几乎不耗电，功耗来自刷新。",
                "6. 如果数字仍有拖影，优先在系统设置调整刷新模式，而不是改应用。"
        };
        for (int i = 0; i < lines.length; i++) {
            if (y > H - tabH - S(24)) break;
            y = wrap(c, lines[i], pad, y, W - 2 * pad, 12, false);
            y += F(8);
        }
    }

    // ---------------------------------------------------------------- 交互

    private static final class Hit {
        final float l, t, r, b;
        final String tag;
        final int idx;
        Hit(float l, float t, float r, float b, String tag, int idx) {
            this.l = l; this.t = t; this.r = r; this.b = b;
            this.tag = tag; this.idx = idx;
        }
        boolean in(float x, float y) { return x >= l && x < r && y >= t && y < b; }
    }

    private Hit findHit(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hh = hits.get(i);
            if (hh.in(x, y)) return hh;
        }
        return null;
    }

    private void cancelLong() {
        if (longRun != null) { h.removeCallbacks(longRun); longRun = null; }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; longFired = false;
                cancelLong();
                final float fx = x, fy = y;
                longRun = new Runnable() {
                    @Override public void run() { onLong(fx, fy); }
                };
                h.postDelayed(longRun, 900);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > 8 * u || Math.abs(y - downY) > 8 * u) cancelLong();
                return true;
            case MotionEvent.ACTION_UP:
                cancelLong();
                if (!longFired) {
                    Hit hh = findHit(x, y);
                    if (hh != null) handle(hh.tag, hh.idx);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLong();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 系统返回键：电量页 / 建议页 / 番茄历史是「页内子视图」，返回应回上一级，
     * 而不是直接退出 App。其余页面返回 false，交回 Activity 默认处理。
     */
    boolean back() {
        if (mode == BATT || mode == TIPS) { mode = SET; invalidate(); return true; }
        if (mode == DAYS && dayFromClock) {
            dayFromClock = false; mode = CLOCK; invalidate(); return true;
        }
        if (mode == POMO && pomoHist) { pomoHist = false; invalidate(); return true; }
        if (mode == HIST_HABIT) { mode = HABIT; invalidate(); return true; }
        if (mode == HIST_TODO_DETAIL) { mode = HIST_TODO; invalidate(); return true; }
        if (mode == HIST_TODO) { mode = TODO; invalidate(); return true; }
        return false;
    }

    private void onLong(float x, float y) {
        Hit hh = findHit(x, y);
        if (hh == null) return;
        if ("main".equals(hh.tag) && mode == CLOCK) {
            longFired = true;                  // 只有真正消费了长按才吞掉这次点击
            mode = SET; invalidate();
        }
    }

    private void handle(String tag, int idx) {
        if ("tab".equals(tag)) {
            if (idx >= 0 && idx < 7) {
                if (idx != HABIT) habitEdit = false;
                pomoHist = false;
                dayFromClock = false;
                mode = idx;
            }
            schedule(); invalidate(); return;
        }
        if ("clock".equals(tag)) { mode = CLOCK; invalidate(); return; }
        if ("main".equals(tag) || "clockmeta".equals(tag)) {
            if (mode == CLOCK) {
                // 三档循环：农历 → 电量 → 空白。农历关掉时只在「电量 / 空白」之间切，
                // 跳过农历档（否则会出现点一次没反应、要点两次）
                if (!st.lunar) clockMeta = (clockMeta == 1) ? 2 : 1;
                else clockMeta = (clockMeta + 1) % 3;
                invalidate();
            }
            return;
        }
        if ("clockday".equals(tag)) {
            mode = DAYS; dayFromClock = true; invalidate(); return;
        }
        if ("cfgtoggle".equals(tag)) { pomoCustom = !pomoCustom; invalidate(); return; }
        // ---- 记录页 ----
        if ("habitrec".equals(tag)) {
            mode = HIST_HABIT; hrecPage = 0; hrecScope = HabitScope.MONTH;
            hrecHabit = HabitScope.ALL_HABITS; hrecMonth = 0; invalidate(); return;
        }
        if ("hrecback".equals(tag)) { mode = HABIT; invalidate(); return; }
        if ("hrecpg".equals(tag)) { hrecPage++; invalidate(); return; }
        if ("hrecrange".equals(tag)) {
            if (idx >= 0 && idx <= HabitScope.ALL) hrecScope = idx;
            // 换范围就清掉月份筛选：从「全部 · 2026-10」切到「本月」再切回来时，
            // 残留的月份筛选会让页面一片空白，看起来像记录丢了
            hrecMonth = 0;
            hrecPage = 0; invalidate(); return;
        }
        if ("hrecseg".equals(tag)) {
            hrecHabit = (idx == 0) ? HabitScope.ALL_HABITS : (idx - 1);
            hrecPage = 0; invalidate(); return;
        }
        if ("hrecmore".equals(tag)) {
            // 习惯多到一排放不下时，「更多」展开完整列表（不硬挤成两行，免得挤压列表高度）
            int n = st.habits.size();
            String[] items = new String[n + 1];
            items[0] = "全部";
            for (int i = 0; i < n; i++) items[i + 1] = st.habits.get(i).name;
            act.choose("按习惯筛选", items, new MainActivity.Icb() {
                @Override public void run(int w) {
                    hrecHabit = (w == 0) ? HabitScope.ALL_HABITS : (w - 1);
                    hrecPage = 0;
                    invalidate();
                }
            });
            return;
        }
        if ("hrecmonth".equals(tag)) {
            // 系统转盘选月：列表选法在记录攒到几年后会变成一长串，转盘一次拨到位
            act.pickMonth(hrecMonth != 0 ? hrecMonth : 0, new MainActivity.Icb() {
                @Override public void run(int ym) {
                    hrecMonth = ym;
                    hrecPage = 0;
                    invalidate();
                }
            });
            return;
        }
        if ("hrecmonthclear".equals(tag)) { hrecMonth = 0; hrecPage = 0; invalidate(); return; }
        if ("hrecexp".equals(tag)) {
            if (idx == 0) act.copy(habitRecordText());
            else if (idx == 1) act.saveToFile("habit-records.txt", "text/plain", habitRecordText());
            else act.saveToFile("habit-records.csv", "text/csv", habitRecordCsv());
            return;
        }
        if ("todorec".equals(tag)) {
            mode = HIST_TODO; recRange = 0; recMonth = 0; recPage = 0; invalidate(); return;
        }
        if ("trecback".equals(tag)) { mode = TODO; invalidate(); return; }
        if ("trecpg".equals(tag)) { recPage++; invalidate(); return; }
        if ("trecrange".equals(tag)) {
            recRange = idx; recMonth = 0; recPage = 0; invalidate(); return;
        }
        if ("trecmonth".equals(tag)) {
            act.pickMonth(recMonth != 0 ? recMonth : 0, new MainActivity.Icb() {
                @Override public void run(int ym) {
                    recMonth = ym;
                    recPage = 0;
                    invalidate();
                }
            });
            return;
        }
        if ("trecmonthclear".equals(tag)) { recMonth = 0; recPage = 0; invalidate(); return; }
        if ("todorecitem".equals(tag)) {
            todoDetail = idx; mode = HIST_TODO_DETAIL; invalidate(); return;
        }
        if ("trecdetailback".equals(tag)) { mode = HIST_TODO; invalidate(); return; }
        if ("trecexp".equals(tag)) {
            if (idx == 0) act.copy(todoRecordText());
            else if (idx == 1) act.saveToFile("todo-records.txt", "text/plain", todoRecordText());
            else act.saveToFile("todo-records.csv", "text/csv", todoRecordCsv());
            return;
        }
        if ("pomohist".equals(tag)) { pomoHist = true; pomoHistPage = 0; invalidate(); return; }
        if ("histback".equals(tag)) { pomoHist = false; invalidate(); return; }
        if ("histpg".equals(tag)) {
            // 页数在绘制时按真实行高算，这里只加一，越界由 drawPomoHistory 归零
            pomoHistPage++; invalidate(); return;
        }
        if ("histrange".equals(tag)) {
            if (idx >= 0 && idx < 3) { pomoHistRange = idx; pomoHistPage = 0; }
            invalidate(); return;
        }
        if ("histexp".equals(tag)) {
            // 导出跟随顶部筛选：本月就只来本月，切到「全部」才是整份历史
            ArrayList<Store.PomoDay> list = pomoHistList();
            String rn = pomoHistRange == 0 ? "本月" : (pomoHistRange == 1 ? "上月" : "全部");
            Calendar mc = Calendar.getInstance();
            if (pomoHistRange == 1) mc.add(Calendar.MONTH, -1);
            String tag2 = pomoHistRange == 2 ? "all-" + Store.fileStamp()
                    : String.format(java.util.Locale.US, "%04d%02d",
                    mc.get(Calendar.YEAR), mc.get(Calendar.MONTH) + 1);
            if (idx == 0) {
                act.copy(st.pomoExportText(list, rn));
            } else if (idx == 1) {
                act.saveToFile("pomo-" + tag2 + ".txt", "text/plain", st.pomoExportText(list, rn));
            } else {
                act.saveToFile("pomo-" + tag2 + ".csv", "text/csv", st.pomoExportCsv(list));
            }
            return;
        }

        // 网格放不下时（摘要态）的编辑入口：逐项弹窗改，保证大字号 / 窄屏也能设时长
        if ("cfgedit".equals(tag)) {
            String[] items = {"工作　" + pomoCfgCur(0) + " 分钟", "短休　" + pomoCfgCur(1) + " 分钟",
                    "长休　" + pomoCfgCur(2) + " 分钟", "轮数　" + pomoCfgCur(3) + " 轮"};
            act.choose("自定义时长", items, new MainActivity.Icb() {
                @Override public void run(int which) {
                    askPomoCfg(which);
                }
            });
            return;
        }

        if ("cfg".equals(tag)) {
            int which = idx / 2, dir = (idx % 2 == 0) ? -1 : 1;
            int step = (which == 0 || which == 2) ? 5 : 1;
            applyPomoCfg(which, pomoCfgCur(which) + dir * step);
            return;
        }

        if ("pm".equals(tag)) {
            if (idx == 0) {
                if (pomoRunning) {
                    pomoLeftMs = Math.max(0, pomoEndAt - SystemClock.elapsedRealtime());
                    pomoRunning = false;
                } else {
                    if (pomoLeftMs <= 0) pomoLeftMs = phaseMinutes() * 60000L;
                    pomoEndAt = SystemClock.elapsedRealtime() + pomoLeftMs;
                    pomoRunning = true;
                }
            } else if (idx == 1) {
                pomoPhase = 0; pomoRound = 1; pomoRunning = false;
                pomoLeftMs = st.pomo.focus * 60000L;
            } else {
                nextPhase(true);
            }
            schedule(); invalidate(); return;
        }

        // ---- 台历主页：直接操作 ----
        if ("dashtodo".equals(tag)) {
            if (idx >= 0 && idx < st.todos.size()) {
                Store.Todo t = st.todos.get(idx);
                t.setDone(!t.done); st.save();
            }
            invalidate(); return;
        }
        if ("dashhabit".equals(tag)) {
            if (idx >= 0 && idx < st.habits.size()) {
                int d = Store.dayOfMonth();
                gridHabit = idx;
                Store.Habit hb = st.habits.get(idx);
                st.mark(hb, d, !hb.days[d]); st.save();
            }
            invalidate(); return;
        }
        if ("habitpick".equals(tag)) {
            int n = st.habits.size();
            if (n == 0) return;
            CharSequence[] names = new CharSequence[n];
            int dom = Store.dayOfMonth();
            for (int i = 0; i < n; i++) {
                Store.Habit hb = st.habits.get(i);
                names[i] = (hb.days[dom] ? "● " : "○ ") + hb.name;
            }
            act.choose("选择项目", names, new MainActivity.Icb() {
                @Override public void run(int w) {
                    if (w >= 0 && w < st.habits.size()) { gridHabit = w; habitEdit = false; }
                    invalidate();
                }
            });
            return;
        }
        if ("gridcell".equals(tag)) {
            int d = idx;
            if (d >= 1 && d <= Store.daysInMonth()
                    && gridHabit >= 0 && gridHabit < st.habits.size()) {
                Store.Habit hb = st.habits.get(gridHabit);
                st.mark(hb, d, !hb.days[d]);
                st.save();
            }
            invalidate(); return;
        }
        if ("dashpg".equals(tag)) {
            int per = Math.max(1, dashRowsPer);
            if (idx == 0) {
                int n = 0;
                for (Store.Todo t : st.todos) if (t.day == Store.today()) n++;
                dashTodoPage = (dashTodoPage + 1) % Math.max(1, (n + per - 1) / per);
            } else {
                dashDayPage = (dashDayPage + 1) % Math.max(1, (st.days.size() + per - 1) / per);
            }
            invalidate(); return;
        }

        // ---- 清单 ----
        if ("todo".equals(tag)) {
            if (idx >= 0 && idx < st.todos.size()) {
                st.todos.get(idx).setDone(!st.todos.get(idx).done);
                st.save();
            }
            invalidate(); return;
        }
        if ("todoadd".equals(tag)) {
            act.input("添加待办", "写点什么…", new MainActivity.Cb() {
                @Override public void run(String s) {
                    st.todos.add(0, new Store.Todo(s, false, Store.today()));
                    st.save(); invalidate();
                }
            });
            return;
        }
        if ("todoedit".equals(tag)) {
            final int i = idx;
            if (i < 0 || i >= st.todos.size()) return;
            final Store.Todo t = st.todos.get(i);
            act.input("编辑待办", "内容", t.text, new MainActivity.Cb() {
                @Override public void run(String s) { t.text = s; st.save(); invalidate(); }
            });
            return;
        }
        if ("tododel".equals(tag)) {
            final int i = idx;
            if (i < 0 || i >= st.todos.size()) return;
            final Store.Todo t = st.todos.get(i);
            act.confirm("删除待办", "确定删除「" + t.text + "」？", new Runnable() {
                @Override public void run() { st.todos.remove(t); st.save(); invalidate(); }
            });
            return;
        }
        if ("todoscope".equals(tag)) { todoScope = idx; invalidate(); return; }
        if ("todopg".equals(tag)) {
            if (idx == 0) todoOpenPage++; else todoDonePage++;
            invalidate(); return;
        }
        if ("todotool".equals(tag)) {
            if (idx == 0) {
                // 工具条第一键：添加一条（原来顶部那个大框）
                act.input("添加待办", "写点什么…", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        st.todos.add(0, new Store.Todo(s, false, Store.today()));
                        st.save(); invalidate();
                    }
                });
            } else if (idx == 1) {
                act.multiInput("批量录入待办", "每行一条，一次添加多条", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        for (String ln : s.split("\\r?\\n")) {
                            ln = ln.trim();
                            if (!ln.isEmpty()) st.todos.add(0, new Store.Todo(ln, false, Store.today()));
                        }
                        st.save(); invalidate();
                    }
                });
            } else if (idx == 2) {
                for (int i : todoIdx(false)) st.todos.get(i).setDone(true);
                st.save(); invalidate();
            } else {
                // 「清除已完成」是真删数据，一下点掉就没了：加一次确认
                int nd = 0;
                for (Store.Todo t : st.todos) if (t.done) nd++;
                if (nd == 0) { invalidate(); return; }
                act.confirm("清除已完成", "将删除 " + nd + " 条已完成待办，确定？", new Runnable() {
                    @Override public void run() {
                        ArrayList<Store.Todo> keep = new ArrayList<>();
                        for (Store.Todo t : st.todos) if (!t.done) keep.add(t);
                        st.todos.clear(); st.todos.addAll(keep);
                        st.save(); invalidate();
                    }
                });
            }
            return;
        }

        // ---- 习惯 ----
        if ("habit".equals(tag)) {
            if (idx >= 0 && idx < st.habits.size()) {
                // 两步确认：第一下只切「当前项」，第二下才改今天的状态。
                // 之前一下就直接打卡，切卡片的时候手一碰当天记录就变了。
                if (idx != gridHabit) {
                    gridHabit = idx;
                } else {
                    Store.Habit hb = st.habits.get(idx);
                    int d = Store.dayOfMonth();
                    st.mark(hb, d, !hb.days[d]);
                    st.save();
                }
            }
            invalidate(); return;
        }
        // ---- 电量页 ----
        if ("battback".equals(tag)) { mode = SET; invalidate(); return; }
        if ("batttab".equals(tag)) { battTab = idx; battPage = 0; invalidate(); return; }
        if ("battpg".equals(tag)) { battPage++; invalidate(); return; }
        if ("hbedit".equals(tag)) { habitEdit = !habitEdit; invalidate(); return; }
        if ("habittool".equals(tag)) {
            if (idx == 0) {
                act.input("添加习惯", "例如：阅读", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        st.habits.add(new Store.Habit(clip(s)));
                        st.save(); invalidate();
                    }
                });
            } else if (idx == 1) {
                act.multiInput("批量添加习惯", "每行一个习惯名称", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        for (String ln : s.split("\\r?\\n")) {
                            ln = ln.trim();
                            if (!ln.isEmpty()) st.habits.add(new Store.Habit(clip(ln)));
                        }
                        st.save(); invalidate();
                    }
                });
            } else {
                int i = gridHabit;
                if (i < 0 || i >= st.habits.size()) return;
                final Store.Habit hb = st.habits.get(i);
                if (idx == 2) {
                    act.input("编辑习惯", "名称", hb.name, new MainActivity.Cb() {
                        @Override public void run(String s) { hb.name = s; st.save(); invalidate(); }
                    });
                } else {
                    act.confirm("删除习惯", "确定删除「" + hb.name + "」？", new Runnable() {
                        @Override public void run() {
                            st.habits.remove(hb);
                            st.save(); invalidate();
                        }
                    });
                }
            }
            return;
        }

        // ---- 倒数日 ----
        if ("daytool".equals(tag)) {
            if (idx == 0) {
                act.input("添加倒数日", "事件名称", new MainActivity.Cb() {
                    @Override public void run(final String name) {
                        act.pickDate(new MainActivity.Cb() {
                            @Override public void run(String ms) {
                                try {
                                    st.days.add(new Store.Day(clip(name), Long.parseLong(ms)));
                                    st.save(); invalidate();
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        });
                    }
                });
            } else {
                act.multiInput("批量录入倒数日", "每行：名称,YYYY-MM-DD", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        for (String ln : s.split("\\r?\\n")) {
                            ln = ln.trim();
                            if (ln.isEmpty()) continue;
                            String[] p = ln.split("[,，]", 2);
                            if (p.length < 2) continue;
                            String[] d = p[1].trim().split("[-/.]");
                            if (d.length != 3) continue;
                            try {
                                int yy = Integer.parseInt(d[0].trim());
                                int mm = Integer.parseInt(d[1].trim());
                                int dd = Integer.parseInt(d[2].trim());
                                Calendar cal = Calendar.getInstance();
                                cal.set(yy, mm - 1, dd, 0, 0, 0);
                                cal.set(Calendar.MILLISECOND, 0);
                                st.days.add(new Store.Day(clip(p[0].trim()), cal.getTimeInMillis()));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        st.save(); invalidate();
                    }
                });
            }
            return;
        }
        if ("dayedit".equals(tag)) {
            final int i = idx;
            if (i < 0 || i >= st.days.size()) return;
            final Store.Day d = st.days.get(i);
            act.choose("编辑「" + d.name + "」",
                    new String[]{"改名称", "改日期", d.pinned ? "取消置顶" : "置顶到台历",
                            d.onClock ? "不在时钟页显示" : "在时钟页显示", "删除"},
                    new MainActivity.Icb() {
                        @Override public void run(int w) {
                            if (w == 0) {
                                act.input("改名称", "事件名称", d.name, new MainActivity.Cb() {
                                    @Override public void run(String s) {
                                        d.name = clip(s); st.save(); invalidate();
                                    }
                                });
                            } else if (w == 1) {
                                act.pickDate(d.millis, new MainActivity.Cb() {
                                    @Override public void run(String ms) {
                                        try {
                                            d.millis = Long.parseLong(ms);
                                            st.save(); invalidate();
                                        } catch (NumberFormatException ignored) {
                                        }
                                    }
                                });
                            } else if (w == 2) {
                                st.togglePin(d); st.save(); invalidate();
                            } else if (w == 3) {
                                st.toggleClockDay(d); st.save(); invalidate();
                            } else {
                                act.confirm("删除事件", "确定删除「" + d.name + "」？", new Runnable() {
                                    @Override public void run() {
                                        st.days.remove(d); st.save(); invalidate();
                                    }
                                });
                            }
                        }
                    });
            return;
        }
        if ("daydel".equals(tag)) {
            final int i = idx;
            if (i < 0 || i >= st.days.size()) return;
            final Store.Day d = st.days.get(i);
            act.confirm("删除事件", "确定删除「" + d.name + "」？", new Runnable() {
                @Override public void run() { st.days.remove(d); st.save(); invalidate(); }
            });
            return;
        }

        // ---- 设置 ----
        if ("set".equals(tag)) {
            switch (idx) {
                case 0: st.showSeconds = !st.showSeconds; st.save(); timeStr = ""; schedule(); invalidate(); break;
                case 1: st.h24 = !st.h24; st.save(); timeStr = ""; invalidate(); break;
                case 2: st.lunar = !st.lunar; st.save(); if (!st.lunar && clockMeta == 0) clockMeta = 2; invalidate(); break;
                case 3: st.keepOn = !st.keepOn; st.save(); act.applyKeepOn(); invalidate(); break;
                case 4: st.oled = !st.oled; st.save(); act.hideBars(); invalidate(); break;
                case 5: {
                    // 七档一次列全的单选弹窗：点哪一档立刻生效并关闭，不用一档一档试。
                    act.fontDialog();
                    break;
                }
                // 索引必须与 drawSettings 的 keys 一一对应：
                // 「番茄钟时长」已去掉，从 case 6 起整体前移一位。
                case 6: mode = BATT; battTab = 0; invalidate(); break;
                case 7: mode = TIPS; invalidate(); break;
                case 8: {
                    // 两份可选：默认只导配置，明细一天一条，几百天会把文件顶得很长
                    act.choose("导出配置", new CharSequence[]{
                                    "仅配置　不含番茄明细",
                                    "全部　含 " + st.pomoHist.size() + " 天番茄明细"},
                            new MainActivity.Icb() {
                                @Override public void run(int w) {
                                    String j = st.backupJson(w == 1);
                                    if (j.isEmpty()) { act.toast("导出失败"); return; }
                                    act.saveToFile("ink-tools-" + Store.fileStamp() + ".json",
                                            "application/json", j);
                                }
                            });
                    break;
                }
                case 9:
                    act.openFile("application/json", new MainActivity.Cb() {
                        @Override public void run(final String s) {
                            if (s == null || s.trim().isEmpty()) { act.toast("文件是空的"); return; }
                            final int days = Store.backupHistDays(s);
                            if (days < 0) { act.toast("不是 Ink Tools 的配置文件"); return; }
                            // 不含番茄明细的备份：只有「覆盖配置」这一种，直接确认
                            if (days == 0) {
                                act.confirm("导入配置",
                                        "将覆盖设置 / 待办 / 打卡 / 倒数日与番茄设置，当前数据不会另存。确定继续？",
                                        new Runnable() {
                                            @Override public void run() { applyRestore(s, false); }
                                        });
                                return;
                            }
                            // 含明细：让用户选是连番茄记录一起覆盖，还是只动配置
                            act.choose("导入配置 · " + days + " 天番茄明细",
                                    new CharSequence[]{
                                            "全部导入　明细覆盖 " + days + " 天",
                                            "只导配置　保留现有记录"},
                                    new MainActivity.Icb() {
                                        @Override public void run(int w) { applyRestore(s, w == 0); }
                                    });
                        }
                    });
                    break;
                case 10:
                    act.confirm("清空数据", "将删除全部待办、习惯与倒数日，且不可恢复。",
                            new Runnable() {
                                @Override public void run() { st.clearAll(); invalidate(); }
                            });
                    break;
                default: break;
            }
        }
    }

    /** 导入收尾：清时钟缓存、让常亮与番茄时长立刻生效、重排闹钟。 */
    private void applyRestore(String json, boolean withPomoHist) {
        if (st.restore(json, withPomoHist)) {
            timeStr = "";
            act.applyKeepOn();
            pomoPhase = 0;
            pomoRound = 1;
            pomoRunning = false;
            pomoLeftMs = st.pomo.focus * 60000L;
            schedule();
            act.toast(withPomoHist ? "已导入（含番茄明细）" : "配置已导入");
        } else {
            act.toast("导入失败：" + st.restoreError);
        }
        invalidate();
    }

    private static String clip(String s) {
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
