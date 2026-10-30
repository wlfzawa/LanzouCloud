package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.Models;

import java.io.File;

/**
 * 下载公共逻辑：下载目标解析、重名处理、存储权限检查、从分享链接入队。
 * 供主界面、文件夹分享、历史记录三处共用，保证行为一致。
 */
final class DownloadHelper {

    private DownloadHelper() {
    }

    /** 下载目标：File 模式（应用目录/自定义路径）或 SAF 模式（系统选择器授权的文件夹） */
    static final class Dest {
        final File file;
        final Uri tree;
        final String label;

        Dest(File file, Uri tree, String label) {
            this.file = file;
            this.tree = tree;
            this.label = label;
        }
    }

    /** 解析下载目录：优先自定义路径，最后应用专属目录（SAF 模式不走这里） */
    static File resolveDir(Activity a, AppPrefs prefs) {
        String custom = prefs.getDownloadDir();
        if (custom != null && !custom.trim().isEmpty()) {
            File f = new File(custom.trim());
            // Android 11+ (API 30) 分区存储：应用无法用 File 直写公共目录，
            // 即使有 WRITE_EXTERNAL_STORAGE 权限也无效，自动回退默认目录。
            if (Build.VERSION.SDK_INT < 30) {
                f.mkdirs();
                return f;
            }
            if (f.canWrite() || f.mkdirs()) {
                return f;
            }
        }
        File dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = a.getFilesDir();
        return dir;
    }

    /** 根据「重名覆盖」设置决定目标文件 */
    static File targetFile(AppPrefs prefs, File dir, String name) {
        if (prefs.isOverwrite()) {
            return new File(dir, name);
        }
        return uniqueFile(dir, name);
    }

    /** 生成一次下载的落地目标：SAF 目录优先，其次 File 路径 */
    static Dest resolveDest(Activity a, AppPrefs prefs, String name) {
        Uri tree = prefs.getDownloadTree();
        if (tree != null) {
            String label = prefs.getDownloadTreeName();
            return new Dest(null, tree, label == null || label.isEmpty() ? "已选文件夹" : label);
        }
        File dir = resolveDir(a, prefs);
        return new Dest(targetFile(prefs, dir, name), null, dir.getPath());
    }

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (true) {
            File g = new File(dir, base + "(" + i + ")" + ext);
            if (!g.exists()) return g;
            i++;
        }
    }

    /**
     * 下载前检查：自定义公共目录 + Android 6.0~12L 需要存储权限。
     * 返回 false 表示本次下载已取消（等待授权）。
     */
    static boolean ensureStorage(Activity a, AppPrefs prefs) {
        // 用系统文件选择器授权过目录的，不需要存储权限
        if (prefs.getDownloadTree() != null) return true;
        String custom = prefs.getDownloadDir();
        if (custom == null || custom.trim().isEmpty()) return true;
        if (!PermissionHelper.needsStorage(a)) return true;
        new AlertDialog.Builder(a)
                .setTitle("需要存储权限")
                .setMessage("下载目录位于公共存储，写入需要「存储」权限。\n\n"
                        + "去授权，或在设置里用「选择文件夹」指定一个目录。")
                .setPositiveButton("去授权", (d, w) -> PermissionHelper.requestStorage(a))
                .setNegativeButton("取消下载", null)
                .show();
        return false;
    }

    /**
     * 解析分享链接并加入下载队列。
     *
     * @param shareUrl  文件分享页地址（https://host/iXXXXXX）
     * @param pwd       提取码，可为空
     * @param knownName 已知文件名（文件夹列表/历史记录里拿到的名字），可为空
     */
    static void startFromShare(final Activity a, final LanzouClient client, final AppPrefs prefs,
                               final String shareUrl, final String pwd, final String knownName) {
        if (!ensureStorage(a, prefs)) return;
        final ProgressDialog pd = ProgressDialog.show(a, "解析", "正在解析下载地址...", true, false);
        new Thread(() -> {
            try {
                Models.DownloadInfo info;
                try {
                    info = client.directUrl(shareUrl, pwd);
                } catch (Exception first) {
                    // 文件夹内有些文件本身没设密码，带上文件夹密码反而会被拒
                    if (pwd == null || pwd.isEmpty()) throw first;
                    info = client.directUrl(shareUrl, "");
                }
                String name = LanzouClient.isUsableFileName(knownName) ? knownName : info.name;
                if (!LanzouClient.isUsableFileName(name)) name = "download.bin";
                name = LanzouClient.localName(new File(name).getName());
                if (name.isEmpty()) name = "download.bin";

                final Dest dest = resolveDest(a, prefs, name);
                final String shownName = name;
                final String durl = info.durl;
                HistoryStore.get(a).add(shareUrl, pwd, shownName, HistoryStore.TYPE_FILE);
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    DownloadCenter.get(a).enqueue(shownName, durl, shareUrl, pwd, dest);
                    Toast.makeText(a, "已加入下载队列：" + shownName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    String msg = e.getMessage();
                    Toast.makeText(a, msg == null || msg.isEmpty() ? "下载失败" : msg,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
