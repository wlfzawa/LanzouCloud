package com.lanzou.cloud.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

/**
 * 剪贴板工具，兼容 Android 2.3 (API 9)：
 * API 11+ 用 android.content.ClipboardManager；
 * API 9/10 用旧的 android.text.ClipboardManager。
 *
 * 说明：读取剪贴板无需运行时权限；Android 10+ 仅应用在前台时可读，
 * Android 12+ 读取时会显示一次系统提示。
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

    /** 读取剪贴板纯文本；没有内容或不可读时返回 null */
    @SuppressWarnings("deprecation")
    public static String read(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 11) {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return null;
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return null;
                CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
                return cs == null ? null : cs.toString();
            } else {
                android.text.ClipboardManager cm = (android.text.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasText()) return null;
                CharSequence cs = cm.getText();
                return cs == null ? null : cs.toString();
            }
        } catch (Throwable t) {
            // 部分 ROM 在后台读剪贴板会抛 SecurityException，静默忽略
            return null;
        }
    }
}
