package com.lanzou.cloud.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * 所有界面的基类：统一处理主题（亮色/深色）的套用与切换后的重建。
 */
public class BaseActivity extends Activity {

    private String appliedTheme;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.apply(this);
        appliedTheme = ThemeHelper.signature(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String now = ThemeHelper.signature(this);
        if (appliedTheme != null && !appliedTheme.equals(now)) {
            appliedTheme = now;
            recreateCompat();
        }
    }

    /** recreate() 是 API 11+，更老的系统只能手动重启当前界面 */
    protected void recreateCompat() {
        if (Build.VERSION.SDK_INT >= 11) {
            recreate();
        } else {
            Intent i = new Intent(this, getClass());
            i.putExtras(getIntent());
            startActivity(i);
            finish();
        }
    }
}
