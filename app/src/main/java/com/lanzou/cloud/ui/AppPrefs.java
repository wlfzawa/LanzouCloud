package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.security.MessageDigest;

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
    /** 旧版本存过剪贴板原文，仅用于升级时清理 */
    private static final String KEY_CLIPBOARD_LAST = "clipboard_last";
    /** 现在只存内容指纹（SHA-256 前 16 字节），无法还原原文 */
    private static final String KEY_CLIPBOARD_HASH = "clipboard_hash";
    private static final String KEY_CLIPBOARD_LAST_TIME = "clipboard_last_time";

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
        // 升级清理：旧版本把剪贴板原文写在这里，可能包含聊天记录，启动时删掉
        if (prefs.contains(KEY_CLIPBOARD_LAST)) {
            prefs.edit().remove(KEY_CLIPBOARD_LAST).apply();
        }
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

    /**
     * 上次处理过的剪贴板内容，避免同一段文字反复弹窗。
     * 只存摘要不存原文：剪贴板里很可能是聊天记录，不应该落到应用配置里。
     */
    public void setLastClipboard(String text) {
        prefs.edit()
                .putString(KEY_CLIPBOARD_HASH, fingerprint(text))
                .putLong(KEY_CLIPBOARD_LAST_TIME, System.currentTimeMillis())
                .apply();
    }

    /**
     * 是否是刚处理过的同一段内容（1 分钟内）。
     * 用时间窗而不是永久去重：用户过一会儿重新复制同一个链接时还能再弹一次。
     */
    public boolean isRecentClipboard(String text) {
        if (text == null) return false;
        String saved = prefs.getString(KEY_CLIPBOARD_HASH, "");
        if (saved.isEmpty() || !saved.equals(fingerprint(text))) return false;
        long t = prefs.getLong(KEY_CLIPBOARD_LAST_TIME, 0);
        return t > 0 && System.currentTimeMillis() - t < 60_000L;
    }

    /** 内容指纹：SHA-256 前 16 字节，单向不可还原 */
    static String fingerprint(String text) {
        if (text == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            final char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[32];
            for (int i = 0; i < 16; i++) {
                int b = d[i] & 0xff;
                out[i * 2] = hex[b >>> 4];
                out[i * 2 + 1] = hex[b & 0x0f];
            }
            return new String(out);
        } catch (Exception e) {
            return "";
        }
    }
}
