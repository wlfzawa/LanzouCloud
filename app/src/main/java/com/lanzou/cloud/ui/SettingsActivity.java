package com.lanzou.cloud.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.MiniFileProvider;
import com.lanzou.cloud.core.SafUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 设置界面：账号、Cookie 导入导出、下载位置、外观主题、剪贴板、历史记录 */
public class SettingsActivity extends BaseActivity {

    private static final int REQ_TREE = 3101;

    private Session session;
    private AppPrefs prefs;
    private TextView textAccount;
    private TextView textDownloadDir;
    private TextView textTheme;
    private CheckBox switchBrowser;
    private CheckBox switchOverwrite;
    private CheckBox switchClipboard;
    /** 刷新界面时回填 CheckBox，避免触发监听弹 toast */
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        session = Session.get(this);
        prefs = AppPrefs.get(this);

        textAccount = findViewById(R.id.text_account);
        textDownloadDir = findViewById(R.id.text_download_dir);
        textTheme = findViewById(R.id.btn_theme);
        switchBrowser = findViewById(R.id.switch_browser);
        switchOverwrite = findViewById(R.id.switch_overwrite);
        switchClipboard = findViewById(R.id.switch_clipboard);
        Button backBtn = findViewById(R.id.settings_back_btn);
        Button switchAccountBtn = findViewById(R.id.btn_switch_account);
        Button manageAccountsBtn = findViewById(R.id.btn_manage_accounts);
        Button exportCookieBtn = findViewById(R.id.btn_export_cookie);
        Button importCookieBtn = findViewById(R.id.btn_import_cookie);
        Button pickDirBtn = findViewById(R.id.btn_pick_dir);
        Button changeDirBtn = findViewById(R.id.btn_change_dir);
        Button historyBtn = findViewById(R.id.btn_history);
        Button browserOpenBtn = findViewById(R.id.btn_browser_open);

        backBtn.setOnClickListener(v -> finish());
        switchAccountBtn.setOnClickListener(v -> confirmLogout());
        manageAccountsBtn.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, AccountsActivity.class)));
        exportCookieBtn.setOnClickListener(v -> exportCookie());
        importCookieBtn.setOnClickListener(v -> CookieImportFlow.pick(this));
        pickDirBtn.setOnClickListener(v -> pickFolder());
        changeDirBtn.setOnClickListener(v -> changeDir());
        historyBtn.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, HistoryActivity.class)));
        browserOpenBtn.setOnClickListener(v -> openLanzouInBrowser());
        textTheme.setOnClickListener(v -> chooseTheme());

        switchBrowser.setChecked(prefs.isDownloadViaBrowser());
        switchBrowser.setOnCheckedChangeListener((btn, checked) -> prefs.setDownloadViaBrowser(checked));
        switchOverwrite.setChecked(prefs.isOverwrite());
        switchOverwrite.setOnCheckedChangeListener((btn, checked) -> prefs.setOverwrite(checked));
        switchClipboard.setChecked(prefs.isClipboardAuto());
        switchClipboard.setOnCheckedChangeListener((btn, checked) -> {
            if (binding) return;
            prefs.setClipboardAuto(checked);
            if (checked) {
                prefs.setClipboardAsked(true);
                toast("已开启：回到主界面时会自动识别剪贴板里的分享链接");
            } else {
                toast("已关闭剪贴板自动拾取");
            }
        });

        TextView textVersion = findViewById(R.id.text_version);
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            textVersion.setText("版本 " + vn);
        } catch (Exception e) {
            textVersion.setText("版本信息不可用");
        }

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        String uid = session.getUid();
        int count = session.listAccounts().size();
        textAccount.setText("当前账号（ylogin）：" + (uid == null ? "未登录" : uid)
                + "   |   已保存账号：" + count + " 个");

        textDownloadDir.setText("当前目录：" + currentDirText());
        textTheme.setText("主题：" + ThemeHelper.modeName(this));
        binding = true;
        switchClipboard.setChecked(prefs.isClipboardAuto());
        binding = false;
    }

    private String currentDirText() {
        Uri tree = prefs.getDownloadTree();
        if (tree != null) {
            String name = prefs.getDownloadTreeName();
            if (name == null || name.isEmpty()) name = SafUtil.displayName(this, tree);
            if (name == null || name.isEmpty()) name = "已选文件夹";
            return "系统文件夹「" + name + "」"
                    + (SafUtil.hasPermission(this, tree) ? "" : "（授权已失效，请重新选择）");
        }
        String dir = prefs.getDownloadDir();
        if (dir == null || dir.trim().isEmpty()) return "默认（应用专属目录）";
        return "自定义路径：" + dir;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将清除本地登录态并返回登录页（已保存的账号列表会保留，可在「账号管理」中一键切回）。确定继续？")
                .setPositiveButton("退出登录", (d, w) -> {
                    session.clearCookie();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 外观

    private void chooseTheme() {
        // API 17 以下没有覆盖 uiMode 的公开 API，只能亮色，避免出现「深色框架 + 亮色页面」
        final String[] items = Build.VERSION.SDK_INT >= 17
                ? new String[]{"跟随系统", "亮色", "深色"}
                : new String[]{"跟随系统", "亮色"};
        int current = prefs.getThemeMode();
        if (current >= items.length) current = 0;
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    prefs.setThemeMode(which);
                    dialog.dismiss();
                    // 立即重建当前界面，回到主界面时其余页面会在 onResume 里自动跟着换
                    recreateCompat();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ Cookie 导出 / 导入

    private void exportCookie() {
        final String cookie = session.getCookie();
        if (cookie == null || cookie.trim().isEmpty()) {
            toast("未登录，没有可导出的 Cookie");
            return;
        }
        String uid = session.getUid();
        new AlertDialog.Builder(this)
                .setTitle("导出 Cookie")
                .setMessage("账号 UID：" + (uid == null ? "未知" : uid)
                        + "\n\n" + cookie)
                .setPositiveButton("复制到剪贴板", (d, w) -> {
                    CopyUtil.copy(this, cookie);
                    toast("Cookie 已复制到剪贴板");
                })
                .setNeutralButton("导出为文件分享", (d, w) -> exportCookieFile(cookie, uid))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把 Cookie 写入应用专属目录的 txt 文件，并通过系统分享面板发送 */
    private void exportCookieFile(final String cookie, final String uid) {
        final ProgressDialog pd = ProgressDialog.show(this, "导出", "正在生成文件...", true, false);
        new Thread(() -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, "lanzou_cookie_" + (uid == null ? "x" : uid) + ".txt");
                FileOutputStream out = new FileOutputStream(f);
                out.write(cookie.getBytes("UTF-8"));
                out.close();
                final Uri uri = MiniFileProvider.uriFor(SettingsActivity.this, f);
                final String path = f.getAbsolutePath();
                runOnUiThread(() -> {
                    pd.dismiss();
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, "导出 Cookie"));
                        toast("已导出：" + path);
                    } catch (Exception e) {
                        toast("没有可用的分享应用：" + e.getMessage());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("导出失败：" + e.getMessage());
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            handleTree(data == null ? null : data.getData());
        } else if (requestCode == CookieImportFlow.REQ_IMPORT_FILE && resultCode == RESULT_OK) {
            CookieImportFlow.handle(this, data, this::refreshUi);
        }
    }

    // ================================================================ 下载目录

    private void changeDir() {
        final String[] options = {
                "使用默认目录（应用专属）",
                SafUtil.isSupported() ? "选择文件夹" : "浏览并选择文件夹",
                "输入路径（高级）"
        };
        new AlertDialog.Builder(this)
                .setTitle("下载目录")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        prefs.setDownloadDir("");
                        prefs.clearDownloadTree();
                        refreshUi();
                        toast("已切换为默认目录");
                    } else if (which == 1) {
                        pickFolder();
                    } else {
                        inputCustomDir();
                    }
                })
                .show();
    }

    /** 让用户直接选一个文件夹：Android 5.0+ 用系统文件选择器，更老的版本用内置浏览 */
    private void pickFolder() {
        if (!SafUtil.isSupported()) {
            browseDir(Environment.getExternalStorageDirectory());
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("已改用内置目录浏览");
            browseDir(Environment.getExternalStorageDirectory());
        }
    }

    private void handleTree(Uri tree) {
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            toast("保存授权失败：" + e.getMessage());
        }
        String name = SafUtil.displayName(this, tree);
        boolean writable = SafUtil.probe(this, tree);
        prefs.setDownloadTree(tree, name.isEmpty() ? "已选文件夹" : name);
        // 系统选择器优先，清掉旧的路径设置避免混淆
        prefs.setDownloadDir("");
        refreshUi();
        if (writable) {
            toast("下载目录已设为「" + (name.isEmpty() ? "已选文件夹" : name) + "」");
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("文件夹可能不可写")
                    .setMessage("已保存这个文件夹，但试写文件失败。可能原因：\n"
                            + "· 该目录只读\n"
                            + "· 文件夹授权已失效\n\n下载时若失败，请换一个文件夹（推荐「Download」）。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    /** API 9~20 的简易目录浏览：列出子目录，点进去或直接选用当前目录 */
    private void browseDir(final File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            toast("目录不可用：" + dir);
            return;
        }
        File[] all = dir.listFiles();
        final List<File> dirs = new ArrayList<File>();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory() && !f.getName().startsWith(".")) dirs.add(f);
            }
        }
        File[] sorted = dirs.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        String[] items = new String[sorted.length + 2];
        items[0] = "✓ 使用当前目录";
        items[1] = ".. 上一级";
        for (int i = 0; i < sorted.length; i++) {
            items[i + 2] = "[目录] " + sorted[i].getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        prefs.clearDownloadTree();
                        prefs.setDownloadDir(dir.getAbsolutePath());
                        refreshUi();
                        toast("下载目录已设为：" + dir.getAbsolutePath());
                    } else if (which == 1) {
                        File parent = dir.getParentFile();
                        if (parent != null) browseDir(parent);
                    } else {
                        browseDir(sorted[which - 2]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void inputCustomDir() {
        final EditText input = new EditText(this);
        input.setHint("/storage/emulated/0/Download");
        String cur = prefs.getDownloadDir();
        if (cur != null && !cur.trim().isEmpty()) {
            input.setText(cur);
            input.setSelection(cur.length());
        }
        new AlertDialog.Builder(this)
                .setTitle("自定义下载目录")
                .setMessage("输入可用的绝对路径。\n若下载失败，建议改用上面的「选择文件夹」。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String p = input.getText().toString().trim();
                    prefs.clearDownloadTree();
                    prefs.setDownloadDir(p);
                    refreshUi();
                    if (p.isEmpty()) {
                        toast("已恢复默认目录");
                    } else if (PermissionHelper.needsStorage(this)) {
                        // Android 6~12L：写公共目录需要运行时存储权限
                        new AlertDialog.Builder(this)
                                .setTitle("需要存储权限")
                                .setMessage("该目录位于公共存储，写入需要「存储」权限。现在去授权？\n\n拒绝的话，下载会自动改用默认目录。")
                                .setPositiveButton("去授权", (d2, w2) ->
                                        PermissionHelper.requestStorage(SettingsActivity.this))
                                .setNegativeButton("暂不", (d2, w2) ->
                                        toast("未授权，公共目录无法写入，建议改用默认目录"))
                                .show();
                    } else {
                        toast("已保存：" + p);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 权限回调

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PermissionHelper.REQ_STORAGE) return;
        if (PermissionHelper.isGranted(grantResults)) {
            toast("存储权限已授予，可以写入该目录");
        } else if (!PermissionHelper.canAskAgain(this)) {
            toast("已拒绝且不再询问：公共目录无法写入。请改用默认目录，或到系统设置里手动开启存储权限");
        } else {
            toast("未授予存储权限：公共目录无法写入，下载会自动改用默认目录");
        }
    }

    // ================================================================ 浏览器

    private void openLanzouInBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://pc.woozooo.com/mydisk.php"));
            startActivity(i);
        } catch (Exception e) {
            toast("没有可用的浏览器：" + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
