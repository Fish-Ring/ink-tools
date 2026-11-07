package ink.tools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Toast;

import java.util.Calendar;

/** 唯一 Activity：只负责窗口标志与原生对话框，界面全部由 Screen 自绘。 */
public class MainActivity extends Activity {

    public interface Cb { void run(String s); }
    public interface Icb { void run(int which); }

    Screen screen;
    Store store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        screen = new Screen(this, store);
        setContentView(screen);
        hideBars();
        applyKeepOn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        screen.start();
    }

    @Override
    protected void onPause() {
        screen.stop();
        super.onPause();
    }

    public void applyKeepOn() {
        if (store.keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * 全屏显示，同时把窗口、状态栏、导航栏都染成页面底色。
     *
     * 以前只做了「隐藏系统栏」，可主题默认的窗口底色是黑的——
     * 一旦状态栏藏不住（部分国产 ROM 强制常驻），或者下拉时被临时拉出，
     * 露出来的就是那条黑边。现在底色跟页面一致，系统图标转成深色，
     * 藏得住是纯白全屏，藏不住也是白底黑图标，两种情况都不会出现黑条。
     */
    public void hideBars() {
        Window w = getWindow();
        // OLED 模式整屏反色，窗口与系统栏要跟着一起黑，否则四周会露白边
        int bg = store.oled ? 0xFF000000 : 0xFFFFFFFF;
        w.setBackgroundDrawable(new ColorDrawable(bg));
        if (Build.VERSION.SDK_INT >= 28) {
            // 让内容铺到挖孔/刘海那一行，而不是被系统缩进一块
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            // 有些 ROM 只认老 flag，两套一起设才能真的铺到状态栏和挖孔那一行
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                // 深色底用浅色图标，白底用深色图标
                int light = store.oled ? 0
                        : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                c.setSystemBarsAppearance(light, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!store.oled) {
                if (Build.VERSION.SDK_INT >= 23) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    /** 字号：七档单选，点一下立即生效，不用来回点着试。 */
    public void fontDialog() {
        final float[] steps = Screen.FONT_STEPS;
        String[] items = new String[steps.length];
        for (int i = 0; i < steps.length; i++) items[i] = String.valueOf(steps[i]);
        new AlertDialog.Builder(this)
                .setTitle("字号")
                .setSingleChoiceItems(items, screen.fontStep(),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                store.fontScale = steps[which];
                                store.save();
                                screen.invalidate();
                                d.dismiss();
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 电量统计：一个弹窗，三条口径（今日 / 本次充电后 / 近 7 天）轮流看。 */
    public void battDialog() {
        final int[] at = {0};
        final AlertDialog[] dlg = new AlertDialog[1];
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("电量统计　1/3")
                .setMessage(screen.battPage(0))
                .setNegativeButton("关闭", null)
                .setPositiveButton("下一个 ›", null)
                .create();
        dlg[0].setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                // 把「下一个」换成不关闭对话框的点击，三条口径原地切换
                dlg[0].getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                at[0] = (at[0] + 1) % 3;
                                dlg[0].setTitle("电量统计　" + (at[0] + 1) + "/3");
                                dlg[0].setMessage(screen.battPage(at[0]));
                            }
                        });
            }
        });
        dlg[0].show();
    }

    public void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    public void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("eink-toolbox", s));
            toast("数据已复制到剪贴板");
        }
    }

    /** 用系统分享面板导出纯文本：不需要任何权限，也没有联网。 */
    public void share(String s) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "番茄记录");
            i.putExtra(android.content.Intent.EXTRA_TEXT, s);
            startActivity(android.content.Intent.createChooser(i, "导出番茄记录"));
        } catch (Exception e) {
            toast("没有可用的分享应用，可改用「复制」");
        }
    }

    // ------------------------------------------------------------ 文件存取（SAF）
    // 走系统文件选择器：不申请任何存储权限，也不联网。

    private static final int REQ_SAVE = 0x51;
    private static final int REQ_OPEN = 0x52;

    private String pendingText;
    private Cb openCb;

    /** 让用户选位置保存一个文本文件（json / csv / txt）。 */
    public void saveToFile(String name, String mime, String text) {
        pendingText = text;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            i.putExtra(android.content.Intent.EXTRA_TITLE, name);
            startActivityForResult(i, REQ_SAVE);
        } catch (Exception e) {
            pendingText = null;
            toast("没有可用的文件选择器");
        }
    }

    /** 让用户选一个文件读成字符串。 */
    public void openFile(String mime, Cb cb) {
        openCb = cb;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            openCb = null;
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        hideBars();
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri u = data.getData();
        if (req == REQ_SAVE) {
            String text = pendingText;
            pendingText = null;
            if (text == null) return;
            java.io.OutputStream os = null;
            try {
                os = getContentResolver().openOutputStream(u, "wt");
                if (os == null) throw new java.io.IOException("no stream");
                os.write(text.getBytes("UTF-8"));
                os.flush();
                toast("已保存");
            } catch (Exception e) {
                toast("保存失败");
            } finally {
                try { if (os != null) os.close(); } catch (Exception ignored) { }
            }
        } else if (req == REQ_OPEN) {
            Cb cb = openCb;
            openCb = null;
            if (cb == null) return;
            java.io.InputStream is = null;
            try {
                is = getContentResolver().openInputStream(u);
                if (is == null) throw new java.io.IOException("no stream");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                cb.run(new String(bo.toByteArray(), "UTF-8"));
            } catch (Exception e) {
                toast("读取失败");
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) { }
            }
            if (screen != null) screen.invalidate();
        }
    }

    /** 单行输入；initial 用于「编辑」时预填旧值。 */
    public void input(String title, String hint, final Cb cb) {
        input(title, hint, "", cb);
    }

    public void input(String title, String hint, String initial, final Cb cb) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(initial);
        et.setSingleLine(true);
        et.setTextSize(16);
        et.setPadding(28, 20, 28, 20);
        if (!initial.isEmpty()) et.setSelection(initial.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (!s.isEmpty()) cb.run(s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 多行输入：批量录入用，避免逐条弹软键盘。 */
    public void multiInput(String title, String hint, final Cb cb) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setLines(8);
        et.setGravity(Gravity.TOP);
        et.setTextSize(15);
        et.setPadding(28, 20, 28, 20);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString();
                        if (!s.trim().isEmpty()) cb.run(s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选择菜单：编辑条目时区分「改名称 / 改日期 / 删除」。 */
    public void choose(String title, CharSequence[] items, final Icb cb) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cb.run(w); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void pickDate(final Cb cb) {
        pickDate(System.currentTimeMillis(), cb);
    }

    public void pickDate(long initial, final Cb cb) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(DatePicker view, int y, int m, int d) {
                Calendar x = Calendar.getInstance();
                x.set(y, m, d, 0, 0, 0);
                x.set(Calendar.MILLISECOND, 0);
                cb.run(String.valueOf(x.getTimeInMillis()));
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** 纯展示：只有一个「知道了」，避免为了一条说明引出一串交互。 */
    public void info(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    public void confirm(String title, String msg, final Runnable ok) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { ok.run(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
