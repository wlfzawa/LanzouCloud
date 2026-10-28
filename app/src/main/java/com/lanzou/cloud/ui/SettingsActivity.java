package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.MiniFileProvider;

import java.io.File;
import java.io.FileOutputStream;

/** 设置界面：账号管理、Cookie 导出、下载位置、下载逻辑、跳转浏览器 */
public class SettingsActivity extends Activity {

    private Session session;
    private AppPrefs prefs;
    private TextView textAccount;
    private TextView textDownloadDir;
    private CheckBox switchBrowser;
    private CheckBox switchOverwrite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        session = Session.get(this);
        prefs = AppPrefs.get(this);

        textAccount = findViewById(R.id.text_account);
        textDownloadDir = findViewById(R.id.text_download_dir);
        switchBrowser = findViewById(R.id.switch_browser);
        switchOverwrite = findViewById(R.id.switch_overwrite);
        Button backBtn = findViewById(R.id.settings_back_btn);
        Button switchAccountBtn = findViewById(R.id.btn_switch_account);
        Button manageAccountsBtn = findViewById(R.id.btn_manage_accounts);
        Button exportCookieBtn = findViewById(R.id.btn_export_cookie);
        Button changeDirBtn = findViewById(R.id.btn_change_dir);
        Button browserOpenBtn = findViewById(R.id.btn_browser_open);

        backBtn.setOnClickListener(v -> finish());
        switchAccountBtn.setOnClickListener(v -> confirmLogout());
        manageAccountsBtn.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, AccountsActivity.class)));
        exportCookieBtn.setOnClickListener(v -> exportCookie());
        changeDirBtn.setOnClickListener(v -> changeDir());
        browserOpenBtn.setOnClickListener(v -> openLanzouInBrowser());

        switchBrowser.setChecked(prefs.isDownloadViaBrowser());
        switchBrowser.setOnCheckedChangeListener((btn, checked) -> prefs.setDownloadViaBrowser(checked));
        switchOverwrite.setChecked(prefs.isOverwrite());
        switchOverwrite.setOnCheckedChangeListener((btn, checked) -> prefs.setOverwrite(checked));

        TextView textVersion = findViewById(R.id.text_version);
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            textVersion.setText("版本 " + vn + "\n系统 API " + Build.VERSION.SDK_INT + " · minSdk 9");
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
        String dir = prefs.getDownloadDir();
        textDownloadDir.setText("当前目录：" + (dir == null || dir.trim().isEmpty()
                ? "默认（应用专属目录）" : dir));
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

    // ================================================================ Cookie 导出

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

    // ================================================================ 下载目录

    private void changeDir() {
        final String[] options = {"使用默认目录（应用专属）", "自定义路径..."};
        new AlertDialog.Builder(this)
                .setTitle("修改下载目录")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        prefs.setDownloadDir("");
                        refreshUi();
                        toast("已切换为默认目录");
                    } else {
                        inputCustomDir();
                    }
                })
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
                .setMessage("输入可写的绝对路径（Android 11+ 受分区存储限制，建议用默认目录）")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String p = input.getText().toString().trim();
                    prefs.setDownloadDir(p);
                    refreshUi();
                    if (p.isEmpty()) {
                        toast("已恢复默认目录");
                    } else if (PermissionHelper.needsStorage(this)) {
                        // Android 6~12L：写公共目录需要运行时存储权限
                        new AlertDialog.Builder(this)
                                .setTitle("需要存储权限")
                                .setMessage("自定义目录位于公共存储，Android 6.0+ 需要「存储」权限才能写入。现在去授权？\n\n拒绝授权的话，下载会自动改用默认目录。")
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
            toast("存储权限已授予，自定义下载目录可正常写入");
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
