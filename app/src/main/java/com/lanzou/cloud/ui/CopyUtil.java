package com.lanzou.cloud.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

/**
 * 剪贴板工具，兼容 Android 2.3 (API 9)：
 * API 11+ 用 android.content.ClipboardManager；
 * API 9/10 用旧的 android.text.ClipboardManager。
 */
public final class CopyUtil {

    private CopyUtil() {
    }

    @SuppressWarnings("deprecation")
    public static void copy(Context ctx, String text) {
        if (Build.VERSION.SDK_INT >= 11) {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("lanzou", text));
            }
        } else {
            android.text.ClipboardManager cm = (android.text.ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setText(text);
            }
        }
    }
}
