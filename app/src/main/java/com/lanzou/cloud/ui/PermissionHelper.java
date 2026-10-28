package com.lanzou.cloud.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 存储权限兼容助手。
 *
 * Android 6.0 (API 23) 起，写公共存储（/storage/emulated/0/...）需要运行时申请
 * WRITE_EXTERNAL_STORAGE；API 33 (Android 13) 起该权限彻底失效，无需申请。
 * API 22 及以下安装时已授权，无需申请。
 */
public final class PermissionHelper {

    public static final int REQ_STORAGE = 2001;
    private static final String PERM = "android.permission.WRITE_EXTERNAL_STORAGE";

    private PermissionHelper() {
    }

    /** 是否需要（且尚未获得）存储权限：仅 API 23~32 有意义 */
    public static boolean needsStorage(Activity act) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 33) return false;
        return act.checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED;
    }

    /** 当前是否已具备写公共存储能力（API 33+ 视同具备，但受分区存储限制） */
    public static boolean hasStorage(Activity act) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 33) return true;
        return act.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** 弹出系统权限申请框（API 23~32 有效，其余版本无操作） */
    public static void requestStorage(Activity act) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 33) {
            act.requestPermissions(new String[]{PERM}, REQ_STORAGE);
        }
    }

    /** 判断权限回调结果是否已授予 */
    public static boolean isGranted(int[] grantResults) {
        return grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否还可以再次弹框（false 表示用户勾选了“不再询问”） */
    public static boolean canAskAgain(Activity act) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 33) return true;
        return act.shouldShowRequestPermissionRationale(PERM);
    }
}
