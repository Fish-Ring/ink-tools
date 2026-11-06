package ink.tools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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

    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
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
