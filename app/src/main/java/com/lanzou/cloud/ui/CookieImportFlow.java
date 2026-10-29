package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.lanzou.cloud.core.LanzouClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cookie 导入流程：选文件 → 读取解析 → 逐个在线校验 → 存进账号列表。
 * 设置页和登录页共用。
 */
public final class CookieImportFlow {

    public static final int REQ_IMPORT_FILE = 3001;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private CookieImportFlow() {
    }

    /** 打开系统文件选择器（json / txt 都行） */
    public static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            a.startActivityForResult(Intent.createChooser(i, "选择 Cookie 文件"), REQ_IMPORT_FILE);
        } catch (Exception e) {
            Toast.makeText(a, "没有可用的文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 处理选择结果：后台解析 + 校验 */
    public static void handle(final Activity a, Intent data, final Runnable onDone) {
        if (data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final ProgressDialog pd = ProgressDialog.show(a, "导入 Cookie", "正在读取文件...", true, false);
        new Thread(() -> {
            String text;
            try {
                text = readText(a, uri);
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(a, "读取文件失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }

            final CookieImporter.Result parsed = CookieImporter.parse(text);
            if (parsed.items.isEmpty()) {
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    StringBuilder sb = new StringBuilder("没有导入任何账号。\n");
                    for (String p : parsed.problems) sb.append("· ").append(p).append('\n');
                    sb.append("\n支持的格式：txt（Cookie 字符串 / cookies.txt）、json（含 cookie 字段或 name/value 列表）");
                    new AlertDialog.Builder(a)
                            .setTitle("导入失败")
                            .setMessage(sb.toString())
                            .setPositiveButton("知道了", null)
                            .show();
                });
                return;
            }

            // 逐个在线校验，避免把过期/残缺的 Cookie 存进账号列表
            final List<CookieImporter.Item> ok = new ArrayList<>();
            final List<String> fail = new ArrayList<>();
            for (int i = 0; i < parsed.items.size(); i++) {
                final CookieImporter.Item it = parsed.items.get(i);
                final int idx = i + 1;
                a.runOnUiThread(() -> {
                    if (idx <= parsed.items.size()) {
                        pd.setMessage("正在验证 " + idx + "/" + parsed.items.size() + "...");
                    }
                });
                try {
                    LanzouClient c = new LanzouClient();
                    c.login(it.cookie);
                    ok.add(it);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    fail.add((it.name == null ? "未命名" : it.name) + "：" + (msg == null ? "验证失败" : msg));
                }
            }

            final Session session = Session.get(a);
            final boolean wasLoggedIn = session.isLoggedIn();
            for (CookieImporter.Item it : ok) {
                String uid = Session.uidOf(it.cookie);
                if (uid != null) {
                    AccountStore.get(a).upsert(it.cookie, uid);
                    if (it.name != null && !it.name.isEmpty()
                            && !it.name.equals("账号 " + uid)) {
                        AccountStore.get(a).rename(uid, it.name);
                    }
                }
            }
            if (!ok.isEmpty() && !wasLoggedIn) {
                session.setCookie(ok.get(0).cookie);
            }

            a.runOnUiThread(() -> {
                pd.dismiss();
                StringBuilder sb = new StringBuilder();
                sb.append("成功导入 ").append(ok.size()).append(" 个账号");
                if (!fail.isEmpty()) sb.append("，失败 ").append(fail.size()).append(" 个");
                sb.append('\n');
                for (CookieImporter.Item it : ok) {
                    sb.append("✓ ").append(it.name).append('\n');
                }
                for (String f : fail) sb.append("✗ ").append(f).append('\n');
                if (!ok.isEmpty() && !wasLoggedIn) {
                    sb.append("\n已设为当前账号，返回即可进入网盘。");
                } else if (!ok.isEmpty()) {
                    sb.append("\n可在「账号管理」里切换。");
                }
                new AlertDialog.Builder(a)
                        .setTitle("导入完成")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show();
                if (onDone != null) onDone.run();
            });
        }).start();
    }

    private static String readText(Activity a, Uri uri) throws Exception {
        InputStream in = a.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("文件过大（超过 4MB）");
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        byte[] bytes = out.toByteArray();
        String text = new String(bytes, "UTF-8");
        // Windows 记事本存的 txt 常见是 GBK，出现替换符时再按 GBK 解一次
        if (text.indexOf('\uFFFD') >= 0) {
            try {
                String gbk = new String(bytes, "GBK");
                if (gbk.indexOf('\uFFFD') < 0) return gbk;
            } catch (Exception ignored) {
            }
        }
        return text;
    }
}
