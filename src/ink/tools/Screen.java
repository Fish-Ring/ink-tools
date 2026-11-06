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
            HABIT = 4, DAYS = 5, SET = 6, TIPS = 7;
    static final String[] TABS = {"台历", "时钟", "番茄", "清单", "打卡", "倒数", "设置"};

    private final MainActivity act;
    private final Store st;

    private float W, H, u, pad, tabH;
    private int mode = DASH;
    private final ArrayList<Hit> hits = new ArrayList<>();

    // 当前页面主时间文本的绘制参数，用于计算脏区
    private boolean hasTime;
    private float tX, tCW, tBase, tAsc, tDesc;
    private String timeStr = "";

    private int clockMeta = 0;                 // 0 农历 / 1 电量 / 2 无
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
    private long lastDay;                     // 跨天检测

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
        // 注意：这里**不能**再按字号缩小画布。
        // 之前做过「字号大 → 设计画布同步放大 → u 变小」，净增益只有 f/g，
        // 1.3 档 1.21 倍、1.75 档 1.29 倍，两档只差 6%，看起来就是「卡在 1.3 不再变」。
        // 现在 u 只由屏幕决定，字号增长 100% 体现在字上；横向挤的地方由 fit() 兜底。
        u = Math.min(W / 420f, H / 620f);      // 420 x 620 设计单位
        pad = 18 * u;
        tabH = 42 * u;
    }

    private float S(float dp) { return dp * u; }

    /**
     * 正文字号跟随设置。>= 52 的「大数字」（时钟、番茄倒计时）不跟随：
     * 这些数字所在卡片的上下留白是按固定值排的，放大后会把边框顶穿。
     * 52 以下（含清单 48 的行高）必须一起缩放，否则文字放大而行高不动就会叠字。
     */
    private float F(float dp) {
        if (dp >= 52) return dp * u;            // 时钟大数字：固定尺寸，不随字号
        float f = st.fontScale;
        // 这里不允许再有任何按方向 / 按字号的封顶：
        // 之前横屏封 1.25，导致横着拿设备时 1.3 / 1.5 / 1.75 / 2.0 全被压成 1.25，
        // 表现就是「调到 1.3 之后再往上完全没反应」。横向挤的地方由 fit() 兜底。
        return dp * u * f;
    }

    /** 固定字号：不跟随设置。用于「塞在固定高度里」的辅助文字（如时钟卡内的农历）。 */
    private float P(float dp) { return dp * u; }

    // ---------------------------------------------------------------- 调度

    void start() {
        started = true;
        timeStr = "";
        lastDay = Store.today();
        if (st.rolloverIfNeeded()) st.save();
        cancelLong();
        schedule();
        invalidate();
    }

    void stop() {
        started = false;
        if (ticker != null) h.removeCallbacks(ticker);
        cancelLong();
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
        float l = tX + d[0] * tCW - 2 * u;
        float r = tX + (d[1] + 1) * tCW + 2 * u;
        float t = tBase + tAsc - 3 * u;
        float b = tBase + tDesc + 3 * u;
        invalidate((int) l, (int) t, (int) r, (int) b);
    }

    // ---------------------------------------------------------------- 番茄钟

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

    private void drawMainTime(Canvas c, String s, float dp, float baseline, float cx) {
        // 大数字不跟随字号（时钟本来就够大），并且按盒子宽高自适应收缩：
        // 6 位带秒的数字在窄屏 + 大字号下曾经会顶穿卡片边框
        float px = S(dp);
        Paint p = Ink.text(px, true, true);
        float total = p.measureText(s);
        float maxW = W - 2 * pad - S(14);
        if (total > maxW) {
            px = px * maxW / total;
            p = Ink.text(px, true, true);
            total = p.measureText(s);
        }
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

    private int battery() {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Throwable ignored) {
        }
        return -1;
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

    /** 右侧一组「改 / 删」线框小按钮，返回左边界。 */
    private float ops(Canvas c, float y, String editTag, String delTag, int idx) {
        // 宽高都要跟着文字走：写死 S(23)/S(20) 时，大字号下「改」会掉出按钮下沿
        float bw = Math.max(S(23), F(10) + S(11)), bh = Math.max(S(20), F(10) + S(9));
        float by = y - bh + F(5);
        float dl = W - pad - bw, el = dl - bw - S(4);
        float ty = by + bh / 2f + F(10) * 0.36f;
        BOX(c, el, by, el + bw, by + bh, 1);
        T(c, "改", el + bw / 2f - TW("改", 10, false, false) / 2f, ty, 10, false, false);
        BOX(c, dl, by, dl + bw, by + bh, 1);
        T(c, "删", dl + bw / 2f - TW("删", 10, false, false) / 2f, ty, 10, false, false);
        HIT(editTag, idx, el, by, el + bw, by + bh);
        HIT(delTag, idx, dl, by, dl + bw, by + bh);
        return el - S(4);
    }

    /** 列内「改 / 删」小按钮，返回按钮区左边界。 */
    private float opsCol(Canvas c, float y, float edge, String editTag, String delTag, int idx) {
        float bw = Math.max(S(19), F(9) + S(9)), bh = Math.max(S(17), F(9) + S(8));
        float by = y - bh + F(4);
        float dl = edge - bw, el = dl - bw - S(3);
        float ty = by + bh / 2f + F(9) * 0.36f;
        BOX(c, el, by, el + bw, by + bh, 1);
        T(c, "改", el + bw / 2f - TW("改", 9, false, false) / 2f, ty, 9, false, false);
        BOX(c, dl, by, dl + bw, by + bh, 1);
        T(c, "删", dl + bw / 2f - TW("删", 9, false, false) / 2f, ty, 9, false, false);
        HIT(editTag, idx, el, by, el + bw, by + bh);
        HIT(delTag, idx, dl, by, dl + bw, by + bh);
        return el - S(3);
    }

    // ---------------------------------------------------------------- onDraw

    @Override
    protected void onDraw(Canvas c) {
        if (u == 0) recalc();
        Ink.theme(st.oled);                    // OLED 模式整屏反色（黑底白字）
        c.drawColor(Ink.BG);
        hits.clear();
        hasTime = false;
        switch (mode) {
            case DASH:  drawDashboard(c); break;
            case CLOCK: drawClock(c);      break;
            case POMO:  drawPomodoro(c);   break;
            case TODO:  drawTodo(c);       break;
            case HABIT: drawHabits(c);     break;
            case DAYS:  drawDays(c);       break;
            case SET:   drawSettings(c);   break;
            case TIPS:  drawTips(c);       break;
            default: break;
        }
        drawTabs(c);
    }

    private void drawTabs(Canvas c) {
        float y = H - tabH;
        HL(c, 0, W, y);
        float w = W / 7f;
        for (int i = 0; i < 7; i++) {
            boolean on = (mode == i);
            String s = fit(TABS[i], 12, w - S(8), on, false);
            T(c, s, i * w + w / 2f - TW(s, 12, on, false) / 2f,
                    y + tabH / 2f + F(4), 12, on, false);
            if (on) Ink.line(c, i * w + w * 0.18f, H - S(3), i * w + w * 0.82f, S(3));
            if (i > 0) {
                Paint p = Ink.stroke(S(1));
                c.drawLine(i * w, y, i * w, H, p);
            }
            HIT("tab", i, i * w, y, (i + 1) * w, H);
        }
    }

    // ---------------------------------------------------------------- 台历主页

    private void drawDashboard(Canvas c) {
        long now = System.currentTimeMillis();
        float y = pad + F(14);
        String dt = dateText(now);
        T(c, dt, pad, y, 18, true, false);
        drawPinned(c, y, pad + TW(dt, 18, true, false) + S(10));
        y += F(12);

        float boxT = y, boxB = y + S(88);
        BOX(c, pad, boxT, W - pad, boxB, 2);
        String ts = ClockText.time(now, st.h24, st.showSeconds);
        drawMainTime(c, ts, st.showSeconds ? 44 : 58, boxT + S(56), W / 2f);
        if (st.lunar) {
            String lun = Lunar.text(now);
            if (lun != null) {
                // 固定 11dp：时钟盒高度固定，跟随字号会压到上面的大数字
                c.drawText(lun, W / 2f - Ink.text(P(11), false, false).measureText(lun) / 2f,
                        boxB - S(8), Ink.text(P(11), false, false));
            }
        }
        HIT("clock", 0, pad, boxT, W - pad, boxB);
        y = boxB + S(10);

        y = drawDashColumns(c, y);
        float weekEnd = drawWeekStrip(c, y + S(10), miniTop() - y - S(12));
        drawMiniRows(c, Math.min(weekEnd + S(8), miniTop()));
    }

    /** 台历右上角：置顶事件，右对齐，点一下进倒数页。不显示「顶」字。 */
    private void drawPinned(Canvas c, float y, float left) {
        Store.Day pin = st.pinnedDay();
        if (pin == null) return;
        float right = W - pad, maxW = right - left;
        if (maxW < S(24)) return;
        long dn = Store.daysFrom(pin.millis);
        String tail = dayDeltaText(dn);
        // 大字号下「还有 43 天」本身就能占满整行，退成「43 天」，保证数字读得到
        if (TW(tail, 11, false, true) > maxW)
            tail = dn == 0 ? "今天" : Math.abs(dn) + " 天";
        String sep = " · ";
        float fixed = TW(sep, 11, false, true) + TW(tail, 11, false, true);
        String shown;
        if (maxW - fixed >= S(12)) {
            shown = fit(pin.name, 11, maxW - fixed, false, true) + sep + tail;
        } else {
            shown = fit(tail, 11, maxW, false, true);
        }
        T(c, shown, right - TW(shown, 11, false, true), y, 11, false, true);
        HIT("tab", DAYS, left, y - F(13), right, y + F(4));
    }

    private static String dayDeltaText(long n) {
        if (n == 0) return "就是今天";
        return n > 0 ? "还有 " + n + " 天" : "已过 " + (-n) + " 天";
    }

    /**
     * 本周打卡概览：7 个小格，格内一个状态点（● 全部完成 / ○ 部分完成 / 空 未打卡）。
     * 只做趋势展示，点整条进打卡页补记；今日逐个打卡用下方「日常」行。
     */
    private float drawWeekStrip(Canvas c, float y, float availH) {
        int nh = st.habits.size();
        if (nh == 0 || availH < F(56)) return y;
        Calendar cal = Calendar.getInstance();
        int dom = cal.get(Calendar.DAY_OF_MONTH);
        int mon = cal.get(Calendar.MONTH), year = cal.get(Calendar.YEAR);
        int back = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // 周一 = 0
        Calendar w = (Calendar) cal.clone();
        w.add(Calendar.DAY_OF_MONTH, -back);

        int done = 0, total = 0;
        for (int i = 0; i < 7; i++) {
            if (w.get(Calendar.MONTH) == mon && w.get(Calendar.YEAR) == year) {
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

        T(c, "本周打卡 ›", pad, y, 11, true, false);
        String s = done + "/" + total + " 次";
        T(c, s, W - pad - TW(s, 9.5f, false, true), y, 9.5f, false, true);

        // 格内两行（周X 日期 / 状态 次数）：高度按「两行字 + 间距」算，
        // 位置按格子比例分配（0.30 / 0.72），大字号下两行也不会叠在一起
        float need = F(9) * 2.5f + S(8);
        float spaceAvail = availH - F(18) - F(12);
        if (spaceAvail < need) return y;                 // 高度不够就整块不画，宁缺不叠
        float cellH = Math.max(need, Math.min(S(46), spaceAvail));
        float top = y + F(16), cw = (W - 2 * pad) / 7f;
        String[] wk = {"一", "二", "三", "四", "五", "六", "日"};
        w = (Calendar) cal.clone();
        w.add(Calendar.DAY_OF_MONTH, -back);
        for (int i = 0; i < 7; i++) {
            float l = pad + i * cw, r = l + cw - S(3);
            BOX(c, l, top, r, top + cellH, 1);
            boolean inMonth = w.get(Calendar.MONTH) == mon && w.get(Calendar.YEAR) == year;
            int d = w.get(Calendar.DAY_OF_MONTH);
            if (inMonth) {
                float c1 = top + cellH * 0.30f, c2 = top + cellH * 0.74f;
                // 格子只有屏宽 1/7，字号调大时这里必须回缩：按格宽反算字号上限，
                // 否则「周一 2」会直接顶出格子右边框（大字号溢格的主因）
                float gpx = Math.min(F(9), (r - l) / 4.6f);
                String top1 = "周" + wk[i] + " " + d;
                if (PW(top1, gpx, false) > r - l - S(3)) top1 = wk[i] + " " + d;
                TP(c, top1, (l + r) / 2f - PW(top1, gpx, false) / 2f,
                        c1 + gpx * 0.36f, gpx, false);
                int cnt = 0;
                for (int k = 0; k < nh; k++) if (st.habits.get(k).days[d]) cnt++;
                String cs = cnt + "/" + nh;
                float csPx = Math.min(F(9), (r - l) / 4.2f);
                float cw2 = PW(cs, csPx, true);
                float cxm = (l + r - cw2 - S(8)) / 2f;
                // 0 次也画空心圈：标记位固定，不会因为没打卡就整块消失
                Ink.dot(c, cxm, c2, Math.min(S(3f), (r - l) * 0.055f), cnt >= nh && nh > 0);
                TP(c, cs, cxm + S(8), c2 + csPx * 0.36f, csPx, true);
                if (d == dom) BOX(c, l + S(1), top + S(1), r - S(1), top + cellH - S(1), 1);
            } else {
                T(c, wk[i], (l + r) / 2f - TW(wk[i], 9, false, false) / 2f,
                        top + cellH / 2f + F(9) * 0.36f, 9, false, false);
            }
            w.add(Calendar.DAY_OF_MONTH, 1);
        }
        HIT("tab", HABIT, pad, y - F(4), W - pad, top + cellH + S(2));
        return top + cellH + S(6);
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
            float w = TW(s, 10, false, false) + S(4) + S(8);
            if (cx + w > avail && cx > 0) { cx = 0; lines++; }
            cx += w;
        }
        return Math.min(3, Math.max(1, lines));
    }

    private float miniTop() {
        // 底部三块：分割线 + 若干行胶囊 + 番茄行；高度全部跟着字号算，不写死
        int lines = habitChipLines();
        // 扣除：分割线上留白 + 胶囊行 + 番茄行（含字底），少扣一点就会压到 tab
        return H - tabH - (F(16) + (lines - 1) * F(18) + F(20) + F(26));
    }

    private static String dayShort(Store.Day d) {
        long n = Store.daysFrom(d.millis);
        if (n == 0) return "今天";
        return n > 0 ? n + "天" : (-n) + "天前";
    }

    /** 每栏显示几行：按屏高算，3–7 行，避免横屏时大片空白。 */
    private int dashPer() {
        float top = pad + F(14) + F(12) + S(88) + S(10);
        // 下方要留给「本周打卡」一条：标题 + 格子 + 间距（随字号变化）
        float stripH = F(18) + F(13) + F(12) + S(14) + F(24);
        float avail = miniTop() - top - stripH - F(30);
        int n = (int) (avail / F(21));
        return Math.max(3, Math.min(7, n));
    }

    /** 台历双栏：左「今日待办」可直接勾选，右「倒数日」列出各项，各自分页。 */
    private float drawDashColumns(Canvas c, float y) {
        ArrayList<Integer> ti = new ArrayList<>();
        int tDone = 0;
        for (int i = 0; i < st.todos.size(); i++) {
            Store.Todo t = st.todos.get(i);
            if (t.day != Store.today()) continue;
            ti.add(i);
            if (t.done) tDone++;
        }
        int dn = st.days.size(), per = dashPer();
        int tp = Math.max(1, (ti.size() + per - 1) / per);
        int dp = Math.max(1, (dn + per - 1) / per);
        if (dashTodoPage >= tp) dashTodoPage = 0;
        if (dashDayPage >= dp) dashDayPage = 0;
        // 高度跟实际行数走，条目少时不留大片空白
        int rows = Math.max(1, Math.min(per, Math.max(ti.size(), dn)));
        float colH = F(30) + rows * F(21) + F(22);

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
                T(c, fit(d.name, 10.5f, right - lx - S(38), false, false),
                        lx, ry, 10.5f, false, false);
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
        T(c, title, x, y + F(14), 12, true, false);
        T(c, cnt, x + w - TW(cnt, 10, false, true), y + F(14), 10, false, true);
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
        String s = pages <= 1 ? "1/1" : "‹ " + (page + 1) + "/" + pages + " ›";
        float bw = TW(s, 10, false, true) + S(14);
        float l = x + (w - bw) / 2f, t = y - F(12), b = y + F(6);
        BOX(c, l, t, l + bw, b, 1);
        T(c, s, l + S(7), y, 10, false, true);
        if (pages > 1) HIT("dashpg", col, l, t, l + bw, b);
    }

    private void drawMiniRows(Canvas c, float startY) {
        int dom = Store.dayOfMonth();
        int lines = habitChipLines();
        float lw = miniLabelW();
        float avail = W - 2 * pad - lw;
        float y = startY;
        HL(c, pad, W - pad, y);
        y += F(16);

        T(c, "日常", pad, y, 11, true, false);
        float hx = pad + lw, hy = y, cx = 0;
        int used = 1;
        for (int i = 0; i < st.habits.size(); i++) {
            Store.Habit x = st.habits.get(i);
            String s = x.name + (x.days[dom] ? " ✓" : " ○");
            float tw = TW(s, 10, false, false), w = tw + S(4) + S(8);
            if (cx + w > avail && cx > 0) {
                if (used >= lines) break;
                cx = 0; hx = pad + lw; hy += F(18); used++;
            }
            BOX(c, hx - S(2), hy - F(11), hx + tw + S(2), hy + F(3), 1);
            T(c, s, hx, hy, 10, false, false);
            HIT("dashhabit", i, hx - S(2), hy - F(11), hx + tw + S(2), hy + F(3));
            hx += w; cx += w;
        }
        if (st.habits.isEmpty()) T(c, "暂无习惯", pad + lw, y, 10, false, false);

        // 习惯多到折行时，番茄行会被推到 tab 栏上：用底边硬钳一次
        y = Math.min(hy + F(20), H - tabH - F(16));
        String pv = (pomoRunning ? phaseName() + " " + pomoText() : "未开始")
                + " · 今日 " + st.pomoDoneToday + " 轮";
        mini(c, "番茄", fit(pv, 11, W - 2 * pad - lw, false, true), y, "tab", POMO);
    }

    private void mini(Canvas c, String k, String v, float y, String tag, int idx) {
        T(c, k, pad, y, 11, true, false);
        T(c, v, pad + miniLabelW(), y, 11, false, true);
        HIT(tag, idx, pad, y - F(13), W - pad, y + F(4));
    }

    // ---------------------------------------------------------------- 全屏时钟

    private void drawClock(Canvas c) {
        long now = System.currentTimeMillis();
        float cy = (H - tabH) / 2f;
        CT(c, dateText(now), cy - F(64), 14, false, false);
        String ts = ClockText.time(now, st.h24, st.showSeconds);
        // 数字与上下两行必须用同一套「固定」基准：数字本身不跟字号（S），
        // 基线若用 F(36) 会随字号下移，而农历那行 >=52 不跟，结果两个字叠在一起
        drawMainTime(c, ts, st.showSeconds ? 62 : 96, cy + S(40), W / 2f);
        HIT("main", 0, pad, cy - S(110), W - pad, cy + S(88));

        String meta = "";
        if (clockMeta == 0) {
            if (st.lunar) {
                String l = Lunar.text(now);
                meta = (l == null) ? "" : l;
            }
        } else if (clockMeta == 1) {
            int b = battery();
            meta = b >= 0 ? "电量 " + b + "%" : "电量 未知";
        }
        if (!meta.isEmpty()) CT(c, meta, cy + S(82), 13, false, false);

    }

    // ---------------------------------------------------------------- 番茄钟

    private void drawPomodoro(Canvas c) {
        if (pomoHist) { drawPomoHistory(c); return; }
        float y = pad + F(20);
        T(c, "番茄钟", pad, y, 17, true, false);
        String state = pomoRunning ? (pomoPhase == 0 ? "专注中" : "休息中")
                : (pomoLeftMs <= 0 ? "已结束" : "已暂停");
        T(c, state, W - pad - TW(state, 12, false, true), y, 12, false, true);
        HL(c, pad, W - pad, y + F(8));
        y += F(30);
        CT(c, "第 " + pomoRound + " 轮 / " + st.pomo.rounds + " · " + phaseName()
                + " · 今日 " + st.pomoDoneToday + " 轮", y, 11, false, false);
        y += F(26);
        drawMainTime(c, pomoText(), 86, y + F(60), W / 2f);
        y += F(88);

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
            BOX(c, l, y, l + sgw, y + S(16), 1);
            float fill = 0f;
            if (i < doneRounds) fill = 1f;
            else if (i == doneRounds && pomoPhase == 0) fill = prog;
            if (fill > 0f) {
                float w = (sgw - S(8)) * fill;
                if (w > S(1)) Ink.line(c, l + S(4), y + S(8), l + S(4) + w, S(4));
            }
        }
        // 间距跟随字号：放大字号时文字顶部不会压到进度条下边框
        y += S(16) + F(12) + S(8);

        T(c, "自定义时长", pad, y, 12, true, false);
        String tog = pomoCustom ? "收起 -" : "展开 +";
        T(c, tog, W - pad - TW(tog, 11, false, false), y, 11, false, false);
        HIT("cfgtoggle", 0, pad, y - F(13), W - pad, y + F(5));
        y += S(10);
        if (pomoCustom) y = drawCfgGrid(c, y);
        y += S(10);

        String[] labs = {"暂停", "重置", "跳过"};
        float bw = (W - 2 * pad - 2 * S(8)) / 3f;
        for (int i = 0; i < 3; i++) {
            float l = pad + i * (bw + S(8)), t = y, b = y + S(38);
            BOX(c, l, t, l + bw, b, 2);
            String lab = (i == 0) ? (pomoRunning ? "暂停" : "继续") : labs[i];
            T(c, lab, l + bw / 2f - TW(lab, 12, true, false) / 2f, t + S(25), 12, true, false);
            if (i == 0 && !pomoRunning) BOX(c, l + S(2), t + S(2), l + bw - S(2), b - S(2), 1);
            HIT("pm", i, l, t, l + bw, b);
        }
        float sy = y + S(38) + S(10);
        drawPomoStats(c, sy, H - tabH - S(8) - sy);
    }

    /** 番茄统计：双列文字（今日 / 近 7 天 / 本月 / 本年 / 累计）+ 近 7 天分钟格。 */
    private void drawPomoStats(Canvas c, float y, float availH) {
        if (availH < F(58)) return;          // 三行文字都放不下就不画，宁缺不压导航栏
        T(c, "专注统计", pad, y + F(12), 12, true, false);
        String hist = "历史 ›";
        float hw = TW(hist, 10, false, false);
        T(c, hist, W - pad - hw, y + F(12), 10, false, false);
        HIT("pomohist", 0, W - pad - hw - S(14), y + F(1), W - pad, y + F(21));
        HL(c, pad, W - pad, y + F(22));
        y += F(34);

        // 双列（左 3 右 2）：五个时间维度压进三行高度，不额外占格子空间。
        // 竖屏只有 420dp 宽，两列各 192dp，字号大一点就会被截断——窄屏用紧凑写法和 9dp
        int[] mins7 = st.pomoLast7MinutesArr();
        boolean near = W < H;
        String[] left = {
                "今日 " + st.pomoDoneToday + (near ? "轮·" : " 轮 · ") + dur(mins7[6]),
                (near ? "近7天 " : "近 7 天 ") + st.pomoLast7Rounds()
                        + (near ? "轮·" : " 轮 · ") + dur(st.pomoLast7Minutes()),
                "本月 " + st.pomoMonthRounds() + (near ? "轮·" : " 轮 · ")
                        + dur(st.pomoMonthMinutes())
        };
        String[] right = {
                "本年 " + st.pomoYearRounds() + (near ? "轮·" : " 轮 · ")
                        + dur(st.pomoYearMinutes()),
                "累计 " + st.pomoAllRounds() + (near ? "轮·" : " 轮 · ")
                        + dur(st.pomoAllMinutes())
        };
        float sd = near ? 9f : 10f;
        float colW = (W - 2 * pad) / 2f;
        for (int i = 0; i < left.length; i++)
            T(c, fit(left[i], sd, colW - S(8), false, true), pad, y + i * F(15), sd, false, true);
        for (int i = 0; i < right.length; i++)
            T(c, fit(right[i], sd, colW - S(8), false, true), pad + colW, y + i * F(15), sd, false, true);
        y += F(46);

        // 本周 7 格：格内是当天专注分钟数，右下角一个「min」标注单位，
        // 不再在网格下方写注释行——那行会被挤到导航栏上。
        // 剩余高度不够一格就整块不画（大字号 + 矮屏），绝不让格子压到导航栏。
        float space = availH - F(34) - F(46) - S(8);
        if (space < S(44)) return;
        float gh = Math.min(S(78), space);
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
        float y = pad + F(20);
        T(c, "番茄历史", pad, y, 17, true, false);
        String back = "‹ 返回";
        float bkw = TW(back, 12, false, false);
        T(c, back, W - pad - bkw, y, 12, false, false);
        HIT("histback", 0, W - pad - bkw - S(16), y - F(17), W - pad, y + F(7));
        HL(c, pad, W - pad, y + F(8));
        y += F(32);

        // 右上：复制 / TXT / CSV —— 后两个落成文件，数据多了也能直接进表格
        float bh = Math.max(S(26), F(24));
        String[] ops = {"复制", "TXT", "CSV"};
        // 宽度按文字实际宽度算（不用固定 dp）：特大字号下三个按钮才不会把左边挤没
        float bw = S(50);
        for (String op : ops) bw = Math.max(bw, TW(op, 10.5f, true, false) + S(16));
        float[] ox = new float[3];
        float cx = W - pad;
        for (int i = 2; i >= 0; i--) { ox[i] = cx - bw; cx -= bw + S(6); }
        for (int i = 0; i < 3; i++) {
            BOX(c, ox[i], y, ox[i] + bw, y + bh, 2);
            T(c, ops[i], ox[i] + bw / 2f - TW(ops[i], 10.5f, true, false) / 2f,
                    y + bh / 2f + F(10.5f) * 0.36f, 10.5f, true, false);
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
        float rh = Math.max(S(26), F(23)), rx = pad;
        for (int i = 0; i < 3; i++) {
            float rw = Math.max(S(58), TW(rg[i], 10.5f, false, false) + S(26));
            BOX(c, rx, y, rx + rw, y + rh, 2);
            if (pomoHistRange == i) BOX(c, rx + S(2), y + S(2), rx + rw - S(2), y + rh - S(2), 1);
            T(c, rg[i], rx + rw / 2f - TW(rg[i], 10.5f, pomoHistRange == i, false) / 2f,
                    y + rh / 2f + F(10.5f) * 0.36f, 10.5f, pomoHistRange == i, false);
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
        T(c, fit(foot, 9, fw, false, true), pad, pageY, 9, false, true);
        listPageBtn(c, pad + S(84), pageY, W - 2 * pad - S(84), pages, pomoHistPage, 0, "histpg");
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

    private float drawCfgGrid(Canvas c, float y) {
        String[] labels = {"工作 · 分钟", "短休 · 分钟", "长休 · 分钟", "轮数 · 轮"};
        int[] vals = {st.pomo.focus, st.pomo.shortBreak, st.pomo.longBreak, st.pomo.rounds};
        // 方框高度跟着文字算：写死 S(50)/S(22) 时，大字号下「+ / -」和数值会顶出框
        float sb = Math.max(S(22), F(13) + S(7));
        float ih = Math.max(S(50), F(13) + S(10) + sb + S(10));
        float gap = S(7), cw = (W - 2 * pad - gap) / 2f;
        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            float l = pad + col * (cw + gap), t = y + row * (ih + gap), r = l + cw, b = t + ih;
            BOX(c, l, t, r, b, 2);
            T(c, labels[i], l + S(11), t + F(13), 9.5f, false, false);

            // 按钮贴底留白 S(7)，不压边框
            float sy = b - S(7) - sb;
            float ml = l + S(11), pl = r - S(11) - sb;
            BOX(c, ml, sy, ml + sb, sy + sb, 2);
            T(c, "-", ml + sb / 2f - TW("-", 13, true, true) / 2f,
                    sy + sb / 2f + F(13) * 0.36f, 13, true, true);
            BOX(c, pl, sy, pl + sb, sy + sb, 2);
            T(c, "+", pl + sb / 2f - TW("+", 13, true, true) / 2f,
                    sy + sb / 2f + F(13) * 0.36f, 13, true, true);

            // 数值严格居中在左右按钮之间，纵向与按钮齐平
            String v = String.valueOf(vals[i]);
            float vx = (ml + sb + pl) / 2f;
            T(c, v, vx - TW(v, 17, true, true) / 2f, sy + sb / 2f + F(17) * 0.34f, 17, true, true);

            HIT("cfg", i * 2, ml, sy, ml + sb, sy + sb);
            HIT("cfg", i * 2 + 1, pl, sy, pl + sb, sy + sb);
        }
        return y + 2 * ih + gap;
    }

    // ---------------------------------------------------------------- ToDo

    /** 按范围与完成状态取出原始索引，保证勾选后自动归入另一区。 */
    private ArrayList<Integer> todoIdx(boolean doneWanted) {
        ArrayList<Integer> r = new ArrayList<>();
        for (int i = 0; i < st.todos.size(); i++) {
            Store.Todo t = st.todos.get(i);
            if (todoScope == 0 && t.day != Store.today()) continue;
            if (t.done != doneWanted) continue;
            r.add(i);
        }
        return r;
    }

    /** 单条清单项：主文字 + 加入时间 / 完成时间，右侧「改 / 删」。 */
    private void drawTodoCell(Canvas c, float x, float y, float right, Store.Todo t, int idx) {
        float h = todoCellH(t);
        float s = F(11), bt = y - s * 0.8f;
        BOX(c, x, bt, x + s, bt + s, 2);
        if (t.done) Ink.check(c, x, bt, s, S(2));
        // 整行先注册；后面注册的「改 / 删」按钮优先命中
        HIT("todo", idx, x, y - F(15), right, y - F(13) + h);
        float opLeft = opsCol(c, y, right, "todoedit", "tododel", idx);
        float tx = x + s + F(6);
        String txt = fit(t.text, 11.5f, opLeft - S(4) - tx, false, false);
        T(c, txt, tx, y, 11.5f, false, false);
        if (t.done) Ink.line(c, tx, y - F(4), tx + TW(txt, 11.5f, false, false), S(2));

        // 加入 / 完成时间：一行放得下就横排，放不下才分成两行
        String add = "加 " + stamp(t.addAt);
        float mw = opLeft - S(4) - tx;
        if (t.done) {
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
        String s = pages <= 1 ? "1/1" : "‹ " + (page + 1) + "/" + pages + " ›";
        float tw = TW(s, 9, false, true);
        float bw = Math.max(tw + S(20), S(56));
        float l = x + (w - bw) / 2f, t = y - F(11), b = y + F(5);
        BOX(c, l, t, l + bw, b, 1);
        T(c, s, l + (bw - tw) / 2f, y, 9, false, true);
        if (pages > 1) HIT(tag, col, l, t, l + bw, b);
    }

    /** 单条高度：已完成多一行时间，行高必须一起加，否则会压到下一条的标题。 */
    private float todoCellH(Store.Todo t) {
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
        float pageY = bottom - S(24);
        float avail = pageY - F(10) - top;

        // 按每一条的真实高度试排，算出本页能放几条；不用固定行高硬除
        int per = 0;
        float used = 0;
        for (int k = 0; k < list.size(); k++) {
            float h = todoCellH(st.todos.get(list.get(k)));
            if (used + h > avail) break;
            used += h; per++;
        }
        per = Math.max(1, per);
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
            drawTodoCell(c, x, cy + F(13), right, t, i);
            cy += todoCellH(t);
        }
        if (list.isEmpty()) T(c, "暂无", x, top + F(18), 10.5f, false, false);
        listPageBtn(c, x, pageY, w, pages, page, col, "todopg");
    }

    private void drawTodo(Canvas c) {
        float y = pad + F(20);
        T(c, "待办清单", pad, y, 17, true, false);
        ArrayList<Integer> open = todoIdx(false), done = todoIdx(true);
        String cnt = done.size() + " / " + (open.size() + done.size());
        T(c, cnt, W - pad - TW(cnt, 12, false, true), y, 12, false, true);
        HL(c, pad, W - pad, y + F(8));
        y += F(24);

        float ib = y + S(36);
        BOX(c, pad, y, W - pad, ib, 2);
        T(c, "＋ 添加一项…", pad + S(10), y + S(18) + F(12) * 0.36f, 12, false, false);
        HIT("todoadd", 0, pad, y, W - pad, ib);
        y = ib + S(8);

        String[] pg = {"今天", "全部"};
        float pw = (W - 2 * pad) / 2f, ph = S(28);
        for (int i = 0; i < 2; i++) {
            float l = pad + i * pw;
            BOX(c, l, y, l + pw, y + ph, 2);
            if (todoScope == i) BOX(c, l + S(2), y + S(2), l + pw - S(2), y + ph - S(2), 1);
            T(c, pg[i], l + pw / 2f - TW(pg[i], 11, todoScope == i, false) / 2f,
                    y + ph / 2f + F(11) * 0.36f, 11, todoScope == i, false);
            HIT("todoscope", i, l, y, l + pw, y + ph);
        }
        y += ph + S(10);

        float gapCol = S(10);
        float colW = (W - 2 * pad - gapCol) / 2f;
        float bottom = H - tabH - S(46) - S(6);
        float mid = pad + colW + gapCol / 2f;
        c.drawLine(mid, y + F(2), mid, bottom, Ink.stroke(S(1)));
        drawTodoColumn(c, pad, y, colW, bottom, open, "未完成", 0);
        drawTodoColumn(c, pad + colW + gapCol, y, colW, bottom, done, "已完成", 1);

        // 工具条移到最下方，与「日常打卡」页一致
        String[] tools = {"批量录入", "全部完成", "清除已完成"};
        float tb = H - tabH - S(46);
        float bw = (W - 2 * pad - 2 * S(6)) / 3f, bh = S(32);
        for (int i = 0; i < 3; i++) {
            float l = pad + i * (bw + S(6));
            BOX(c, l, tb, l + bw, tb + bh, 2);
            String s = fit(tools[i], 10.5f, bw - S(10), true, false);
            T(c, s, l + bw / 2f - TW(s, 10.5f, true, false) / 2f,
                    tb + bh / 2f + F(10.5f) * 0.36f, 10.5f, true, false);
            HIT("todotool", i, l, tb, l + bw, tb + bh);
        }
    }

    // ---------------------------------------------------------------- 习惯打卡

    private void drawHabits(Canvas c) {
        float y = pad + F(20);
        T(c, "日常打卡", pad, y, 17, true, false);
        String mon = (Calendar.getInstance().get(Calendar.MONTH) + 1) + " 月";
        T(c, mon, W - pad - TW(mon, 12, false, true), y, 12, false, true);
        HL(c, pad, W - pad, y + F(8));
        y += F(24);

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
        float toolBH = Math.max(S(32), F(10.5f) * 1.75f);
        float ib = H - tabH - S(6) - toolBH;

        if (nh == 0) {
            T(c, "还没有习惯", pad, y + F(16), 11, false, false);
            T(c, "用下面的按钮添加一项，之后每天点一下即可打卡", pad, y + F(36), 10, false, false);
        } else {
            if (gridHabit < 0 || gridHabit >= nh) gridHabit = 0;
            y = drawHabitPicker(c, y);
            drawMonthGrid(c, y + S(6), ib);
        }

        int nt = nh == 0 ? 2 : 4;
        String[] tools = {"＋ 添加", "批量", "改当前", "删当前"};
        float bw2 = (W - 2 * pad - (nt - 1) * S(6)) / nt;
        float bh = Math.max(S(32), F(10.5f) * 1.75f);   // 按钮跟着字长高，字不会掉出框
        for (int i = 0; i < nt; i++) {
            float l = pad + i * (bw2 + S(6));
            BOX(c, l, ib, l + bw2, ib + bh, 2);
            String s = fit(tools[i], 10.5f, bw2 - S(10), true, false);
            T(c, s, l + bw2 / 2f - TW(s, 10.5f, true, false) / 2f,
                    ib + bh / 2f + F(10.5f) * 0.36f, 10.5f, true, false);
            HIT("habittool", i, l, ib, l + bw2, ib + bh);
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

        float chipH = Math.max(S(26), F(10.5f) * 1.9f);
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
        float bw = Math.max(S(48), F(30)), bh = Math.max(S(20), F(19));
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
        T(c, rs, rsx, y + F(13), 9.5f, false, true);
        HL(c, pad, W - pad, y + F(20));
        y += F(30);

        // 星期行缩小：它只是列标题，用固定像素画（不跟字号），占一行高就够，
        // 省下来的高度全部让给日期格
        String[] wk = {"一", "二", "三", "四", "五", "六", "日"};
        float cw = (W - 2 * pad) / 7f;
        float wpx = Math.min(S(9), cw * 0.34f);
        for (int i = 0; i < 7; i++) {
            TP(c, wk[i], pad + i * cw + cw / 2f - PW(wk[i], wpx, false) / 2f,
                    y + wpx, wpx, false);
        }
        y += wpx * 1.55f;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // 周一为第 0 列
        int rows = (lead + dim + 6) / 7;
        // 格高把内容区的剩余空间全部吃掉（只留 84dp 上限防止少数几行时过分空旷）。
        // 之前卡在 66dp，竖屏 5~6 行月历底部留一大片空白，格子反而挤得放不下日期和点。
        float gh = Math.min(S(84), (ib - S(6) - y) / rows);
        if (gh < S(18)) return;                     // 实在放不下就整块不画，宁缺不叠

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

    private void drawDays(Canvas c) {
        float y = pad + F(20);
        T(c, "倒数日", pad, y, 17, true, false);
        String cnt = st.days.size() + " 项";
        T(c, cnt, W - pad - TW(cnt, 12, false, true), y, 12, false, true);
        HL(c, pad, W - pad, y + F(8));
        y += F(26);

        Store.Day primary = st.primaryDay();
        if (primary != null) {
            long n = Store.daysFrom(primary.millis);
            // 卡片高度必须容得下 48dp 的大数字（它会跟着字号放大），
            // 写死 F(80) 时大字号会把「43」顶到上面的标题行上
            float cardH = Math.max(S(80), F(20) + F(48) * 1.15f + S(14));
            float b = y + cardH;
            BOX(c, pad, y, W - pad, b, 2);
            String nm = (primary.pinned ? "顶 · " : "")
                    + fit(primary.name, 12, W - 2 * pad - S(30), true, false);
            T(c, nm, pad + S(12), y + F(20), 12, true, false);
            if (n == 0) {
                T(c, "就是今天", pad + S(14), b - F(14), 20, true, false);
            } else {
                String big = String.valueOf(Math.abs(n));
                T(c, big, pad + S(14), b - F(10), 48, true, true);
                T(c, n >= 0 ? "天" : "天前",
                        pad + S(14) + TW(big, 48, true, true) + S(8), b - F(10), 14, false, false);
            }
            String ds = dateShort(primary.millis);
            T(c, ds, W - pad - S(12) - TW(ds, 11, false, true), b - F(10), 11, false, true);
            HIT("dayedit", st.days.indexOf(primary), pad, y, W - pad, b);
            y = b + S(12);
        }

        float ib = H - tabH - S(46);
        for (int i = 0; i < st.days.size(); i++) {
            if (y + F(20) + F(8) > ib - S(4)) break;
            Store.Day d = st.days.get(i);
            if (d == primary) continue;
            long n = Store.daysFrom(d.millis);
            float ry = y + F(20);
            float right = ops(c, ry, "dayedit", "daydel", i);
            String v = dayDeltaText(n);
            float vx = right - S(8) - TW(v, 11.5f, false, true);
            T(c, fit((d.pinned ? "顶 " : "") + d.name, 13, vx - S(8) - pad, false, false),
                    pad, ry, 13, false, false);
            T(c, v, vx, ry, 11.5f, false, true);
            HIT("dayedit", i, pad, ry - F(15), right, ry + F(8));
            y += F(34);
        }

        String[] tools = {"＋ 添加事件", "批量录入"};
        float bw = (W - 2 * pad - S(6)) / 2f;
        float bh2 = Math.max(S(36), F(11) * 1.9f);
        for (int i = 0; i < 2; i++) {
            float l = pad + i * (bw + S(6));
            BOX(c, l, ib, l + bw, ib + bh2, 2);
            String s = fit(tools[i], 11, bw - S(10), true, false);
            T(c, s, l + bw / 2f - TW(s, 11, true, false) / 2f,
                    ib + bh2 / 2f + F(11) * 0.36f, 11, true, false);
            HIT("daytool", i, l, ib, l + bw, ib + bh2);
        }
    }

    // ---------------------------------------------------------------- 设置 / 建议

    /** 字号档位名。区间比较，避免浮点相等判断失准。 */
    /** 7 档字号：点「字号」行按顺序循环，最高 2.0 倍。 */
    private static final float[] FONT_STEPS = {0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f};

    /** 当前字号落在第几档（旧版本遗留的 0.85 也能对上最近档）。 */
    private int fontStep() {
        int at = 2;
        float best = 9f;
        for (int i = 0; i < FONT_STEPS.length; i++) {
            float dd = Math.abs(FONT_STEPS[i] - st.fontScale);
            if (dd < best) { best = dd; at = i; }
        }
        return at;
    }

    private String fontName() {
        float f = st.fontScale;
        if (f < 0.95f) return "小";
        if (f < 1.08f) return "标准";
        if (f < 1.22f) return "大";
        if (f < 1.4f) return "特大";
        if (f < 1.62f) return "超大";
        if (f < 1.88f) return "巨无霸";
        return "超巨";
    }

    private String fontScaleText() {
        return String.valueOf(Math.round(st.fontScale * 100) / 100f);
    }

    private void drawSettings(Canvas c) {
        float y = pad + F(20);
        T(c, "设置", pad, y, 17, true, false);
        T(c, "v0.0.18", W - pad - TW("v0.0.18", 12, false, true), y, 12, false, true);
        HL(c, pad, W - pad, y + F(8));
        y += F(22);

        String[] keys = {"显示秒", "时间制", "农历", "保持常亮", "OLED 模式", "字号",
                "番茄钟时长", "墨水屏使用建议", "导出配置", "导入配置", "清空数据"};
        String[] vals = {
                st.showSeconds ? "开" : "关",
                st.h24 ? "24 小时" : "12 小时",
                st.lunar ? "开" : "关",
                st.keepOn ? "开" : "关",
                st.oled ? "黑底白字" : "关",
                fontName() + " " + fontScaleText() + "　" + (fontStep() + 1) + "/7",
                st.pomo.focus + "/" + st.pomo.shortBreak + "/" + st.pomo.longBreak + " ›",
                "›", "JSON ›", "JSON ›", "›"
        };
        // 行距自适应：9 行 × F(38) 在大字号竖屏会整体溢出到 tab 栏，
        // 这里按剩余高度算行距（下限略高于字高，绝不叠行）
        float perRow = Math.min(F(38), (H - tabH - S(22) - y) / keys.length);
        perRow = Math.max(F(15), perRow);
        for (int i = 0; i < keys.length; i++) {
            float ry = y + perRow * 0.62f;
            T(c, keys[i], pad, ry, 13, false, false);
            boolean on = vals[i].equals("开");
            T(c, vals[i], W - pad - TW(vals[i], 12, on, true), ry, 12, on, true);
            if (on) {
                float vx = W - pad - TW(vals[i], 12, true, true);
                Ink.line(c, vx, ry + F(4), W - pad, S(2));
            }
            HIT("set", i, pad, ry - perRow * 0.55f, W - pad, ry + perRow * 0.32f);
            y += perRow;
        }

    }

    private void drawTips(Canvas c) {
        float y = pad + F(20);
        T(c, "‹ 返回设置", pad, y, 13, true, false);
        HIT("tab", SET, pad, y - F(16), W - pad, y + F(8));
        y += F(30);
        HL(c, pad, W - pad, y);
        y += F(22);
        String[] lines = {
                "墨水屏使用建议",
                "1. 在系统设置里为本应用选择「普通 / 均衡刷新」，不要用极速模式，残影更少。",
                "2. 开启系统「自动清除残影 / 定期全刷」。应用不再自行插入全刷，避免重复闪烁。",
                "3. 默认精度为「时:分」，每分钟只重绘变化的数字位。打开秒后刷新次数约增加 60 倍，更耗电、更容易留残影。",
                "4. 界面只用黑与白，状态用线框、勾与删除线表达，没有实心色块与反白。",
                "5. 保持常亮只影响系统休眠，不会增加墨水屏耗电：静态画面几乎不耗电，功耗来自刷新。",
                "6. 如果数字仍有拖影，优先在系统设置调整刷新模式，而不是改应用。"
        };
        for (int i = 0; i < lines.length; i++) {
            if (y > H - tabH - S(24)) break;
            y = wrap(c, lines[i], pad, y, W - 2 * pad, i == 0 ? 16 : 12, i == 0);
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
                mode = idx;
            }
            schedule(); invalidate(); return;
        }
        if ("clock".equals(tag)) { mode = CLOCK; invalidate(); return; }
        if ("main".equals(tag)) {
            if (mode == CLOCK) {
                // 三档循环：农历 → 电量 → 空白。农历关掉时只在「电量 / 空白」之间切，
                // 跳过农历档（否则会出现点一次没反应、要点两次）
                if (!st.lunar) clockMeta = (clockMeta == 1) ? 2 : 1;
                else clockMeta = (clockMeta + 1) % 3;
                invalidate();
            }
            return;
        }
        if ("cfgtoggle".equals(tag)) { pomoCustom = !pomoCustom; invalidate(); return; }
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

        if ("cfg".equals(tag)) {
            int which = idx / 2, dir = (idx % 2 == 0) ? -1 : 1;
            int step = (which == 0 || which == 2) ? 5 : 1;
            int[] lo = {5, 1, 5, 2}, hi = {90, 30, 60, 8};
            int[] cur = {st.pomo.focus, st.pomo.shortBreak, st.pomo.longBreak, st.pomo.rounds};
            int v = Math.max(lo[which], Math.min(hi[which], cur[which] + dir * step));
            switch (which) {
                case 0: st.pomo.focus = v;
                    if (pomoPhase == 0 && !pomoRunning) pomoLeftMs = v * 60000L;
                    break;
                case 1: st.pomo.shortBreak = v; break;
                case 2: st.pomo.longBreak = v; break;
                case 3: st.pomo.rounds = v; break;
                default: break;
            }
            st.save(); invalidate(); return;
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
                hb.days[d] = !hb.days[d]; st.save();
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
                hb.days[d] = !hb.days[d];
                st.save();
            }
            invalidate(); return;
        }
        if ("dashpg".equals(tag)) {
            int per = dashPer();
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
                act.multiInput("批量录入待办", "每行一条，一次添加多条", new MainActivity.Cb() {
                    @Override public void run(String s) {
                        for (String ln : s.split("\\r?\\n")) {
                            ln = ln.trim();
                            if (!ln.isEmpty()) st.todos.add(0, new Store.Todo(ln, false, Store.today()));
                        }
                        st.save(); invalidate();
                    }
                });
            } else if (idx == 1) {
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
                    hb.days[d] = !hb.days[d];
                    st.save();
                }
            }
            invalidate(); return;
        }
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
                    new String[]{"改名称", "改日期", d.pinned ? "取消置顶" : "置顶到台历", "删除"},
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
                case 4: st.oled = !st.oled; st.save(); invalidate(); break;
                case 5: {
                    // 点一下直接进下一档（到顶回到底）：不再弹窗，改完立刻看得见。
                    // 之前走系统列表弹窗，在墨水屏上点中哪一项全凭手感，很容易「点了没反应」。
                    float[] opts = FONT_STEPS;
                    int at = fontStep();
                    st.fontScale = opts[(at + 1) % opts.length];
                    st.save();
                    invalidate();
                    break;
                }
                case 6: mode = POMO; schedule(); invalidate(); break;
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
