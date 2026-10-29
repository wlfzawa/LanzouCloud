package com.lanzou.cloud.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.lanzou.cloud.R;

/**
 * 主题助手：亮色 / 深色 / 跟随系统。
 *
 * 应用没有用 AndroidX，因此不能用 AppCompatDelegate。这里的做法是：
 * 1. 在 attachBaseContext 里用 createConfigurationContext 覆盖 uiMode（API 17+），
 *    这样 @color / drawable 会自动命中 values-night，连 AlertDialog 的文字颜色也会跟着变；
 * 2. 在 onCreate 里按当前模式选 Light / Dark 两套原生主题；
 * 3. 用户切换模式后，各个 Activity 在 onResume 里发现签名变化就 recreate()。
 *
 * API 9~16 没有手动覆盖 uiMode 的公开 API，只能跟随系统（系统本身没有深色模式，
 * 即始终亮色），功能不受影响。
 */
public final class ThemeHelper {

    public static final int MODE_AUTO = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private ThemeHelper() {
    }

    public static int mode(Context c) {
        return AppPrefs.get(c).getThemeMode();
    }

    public static boolean isSystemDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前是否应该用深色外观 */
    public static boolean isDark(Context c) {
        int m = mode(c);
        if (m == MODE_DARK) return true;
        if (m == MODE_LIGHT) return false;
        return isSystemDark(c);
    }

    /** 供 Activity.attachBaseContext 使用：按设置覆盖资源里的夜间模式 */
    public static Context wrap(Context base) {
        int m = mode(base);
        if (Build.VERSION.SDK_INT < 17 || m == MODE_AUTO) return base;
        try {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            int night = (m == MODE_DARK) ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO;
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }

    /** 在 setContentView 之前调用，选中对应的原生主题 */
    public static void apply(Activity a) {
        boolean dark = isDark(a) && Build.VERSION.SDK_INT >= 17;
        a.setTheme(dark ? R.style.Theme_LanzouCloud_Dark : R.style.Theme_LanzouCloud);
    }

    /** 外观签名：模式 + 实际明暗，用来判断是否需要重建界面 */
    public static String signature(Context c) {
        return mode(c) + ":" + (isDark(c) ? "dark" : "light");
    }

    public static String modeName(Context c) {
        int m = mode(c);
        if (m == MODE_DARK) return "深色";
        if (m == MODE_LIGHT) return "亮色";
        return "跟随系统（当前" + (isSystemDark(c) ? "深色" : "亮色") + "）";
    }
}
