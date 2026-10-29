package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 手动覆盖夜间模式的 API 17+ 实现。
 * 单独成类，避免 Android 2.3~4.1 加载 ThemeHelper 时碰到不存在的 Context#createConfigurationContext。
 */
final class ThemeHelper17 {

    private ThemeHelper17() {
    }

    static Context wrap(Context base, boolean dark) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }
}
