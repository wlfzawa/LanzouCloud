package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用设置：下载位置、下载逻辑（跳转浏览器 / 重名处理） */
public class AppPrefs {

    private static final String PREFS = "lanzou_settings";
    private static final String KEY_DOWNLOAD_DIR = "download_dir";
    private static final String KEY_DOWNLOAD_BROWSER = "download_browser";
    private static final String KEY_OVERWRITE = "overwrite";

    private static AppPrefs instance;

    public static synchronized AppPrefs get(Context ctx) {
        if (instance == null) {
            instance = new AppPrefs(ctx.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;

    private AppPrefs(Context app) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 自定义下载目录（空串表示使用应用专属目录） */
    public String getDownloadDir() {
        return prefs.getString(KEY_DOWNLOAD_DIR, "");
    }

    public void setDownloadDir(String dir) {
        prefs.edit().putString(KEY_DOWNLOAD_DIR, dir == null ? "" : dir).apply();
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
}
