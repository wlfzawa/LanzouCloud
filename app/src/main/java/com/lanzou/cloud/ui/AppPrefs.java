package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** 应用设置：下载位置、下载逻辑、外观主题、剪贴板拾取 */
public class AppPrefs {

    private static final String PREFS = "lanzou_settings";

    private static final String KEY_DOWNLOAD_DIR = "download_dir";
    private static final String KEY_DOWNLOAD_TREE = "download_tree";
    private static final String KEY_DOWNLOAD_TREE_NAME = "download_tree_name";
    private static final String KEY_DOWNLOAD_BROWSER = "download_browser";
    private static final String KEY_OVERWRITE = "overwrite";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_CLIPBOARD_AUTO = "clipboard_auto";
    private static final String KEY_CLIPBOARD_ASKED = "clipboard_asked";
    private static final String KEY_CLIPBOARD_LAST = "clipboard_last";

    private static AppPrefs instance;

    public static synchronized AppPrefs get(Context ctx) {
        if (instance == null) {
            Context app = ctx.getApplicationContext();
            instance = new AppPrefs(app == null ? ctx : app);
        }
        return instance;
    }

    private final SharedPreferences prefs;

    private AppPrefs(Context app) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================================================================ 下载位置

    /** 自定义下载目录（空串表示使用应用专属目录） */
    public String getDownloadDir() {
        return prefs.getString(KEY_DOWNLOAD_DIR, "");
    }

    public void setDownloadDir(String dir) {
        prefs.edit().putString(KEY_DOWNLOAD_DIR, dir == null ? "" : dir).apply();
    }

    /** 系统文件选择器授权的目录（SAF），优先于 getDownloadDir */
    public Uri getDownloadTree() {
        String s = prefs.getString(KEY_DOWNLOAD_TREE, "");
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public String getDownloadTreeUri() {
        return prefs.getString(KEY_DOWNLOAD_TREE, "");
    }

    public String getDownloadTreeName() {
        return prefs.getString(KEY_DOWNLOAD_TREE_NAME, "");
    }

    public void setDownloadTree(Uri uri, String displayName) {
        prefs.edit()
                .putString(KEY_DOWNLOAD_TREE, uri == null ? "" : uri.toString())
                .putString(KEY_DOWNLOAD_TREE_NAME, displayName == null ? "" : displayName)
                .apply();
    }

    public void clearDownloadTree() {
        prefs.edit().remove(KEY_DOWNLOAD_TREE).remove(KEY_DOWNLOAD_TREE_NAME).apply();
    }

    /** 下载时是否跳转系统浏览器 */
    public boolean isDownloadViaBrowser() {
        return prefs.getBoolean(KEY_DOWNLOAD_BROWSER, false);
    }

    public void setDownloadViaBrowser(boolean v) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_BROWSER, v).apply();
    }

    /** 重名文件是否直接覆盖（false = 自动重命名） */
    public boolean isOverwrite() {
        return prefs.getBoolean(KEY_OVERWRITE, false);
    }

    public void setOverwrite(boolean v) {
        prefs.edit().putBoolean(KEY_OVERWRITE, v).apply();
    }

    // ================================================================ 外观

    /** 0=跟随系统 1=亮色 2=深色 */
    public int getThemeMode() {
        return prefs.getInt(KEY_THEME_MODE, 0);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    // ================================================================ 剪贴板

    /** 是否开启剪贴板自动拾取 */
    public boolean isClipboardAuto() {
        return prefs.getBoolean(KEY_CLIPBOARD_AUTO, false);
    }

    public void setClipboardAuto(boolean v) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_AUTO, v).apply();
    }

    /** 是否已经问过用户（首次启动弹一次说明） */
    public boolean isClipboardAsked() {
        return prefs.getBoolean(KEY_CLIPBOARD_ASKED, false);
    }

    public void setClipboardAsked(boolean v) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_ASKED, v).apply();
    }

    /** 上次处理过的剪贴板内容，避免同一段文字反复弹窗 */
    public String getLastClipboard() {
        return prefs.getString(KEY_CLIPBOARD_LAST, "");
    }

    public void setLastClipboard(String text) {
        prefs.edit().putString(KEY_CLIPBOARD_LAST, text == null ? "" : text).apply();
    }
}
