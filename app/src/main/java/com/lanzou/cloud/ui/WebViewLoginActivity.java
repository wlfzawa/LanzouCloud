package com.lanzou.cloud.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;

/**
 * 网页登录：内嵌 WebView 打开蓝奏云网页，用户登录后自动提取 Cookie 并进入主界面。
 */
public class WebViewLoginActivity extends Activity {

    private Session session;
    private WebView webView;
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview_login);
        session = Session.get(this);

        // 兼容 Android 4.4（API 19/20 需要先创建 CookieSyncManager）
        if (Build.VERSION.SDK_INT < 21) {
            CookieSyncManager.createInstance(this);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 用桌面 UA，避免被跳转到移动版页面
        s.setUserAgentString(LanzouClient.UA);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkAndLogin();
            }
        });

        webView.loadUrl("https://pc.woozooo.com/mydisk.php");
    }

    private void checkAndLogin() {
        if (done) return;
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().flush();
        }
        String cookie = CookieManager.getInstance().getCookie("https://pc.woozooo.com");
        if (cookie == null || !cookie.contains("ylogin")) return;
        try {
            LanzouClient.parseCookie(cookie); // 校验必须含 ylogin
            done = true;
            session.setCookie(cookie);
            Toast.makeText(this, "网页登录成功", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        } catch (LanzouException ignored) {
            // Cookie 尚不完整，继续等待用户在网页中完成登录
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }
}
