package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.LanzouClient;

/** 登录页：粘贴浏览器 Cookie 登录 */
public class LoginActivity extends Activity {

    private EditText cookieInput;
    private Session session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = Session.get(this);

        // 已有登录态直接进主界面（force=true 时强制停留在登录页，用于登录新账号）
        boolean force = getIntent().getBooleanExtra("force", false);
        if (session.isLoggedIn() && !force) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);
        cookieInput = findViewById(R.id.cookie_input);
        Button loginBtn = findViewById(R.id.login_btn);
        loginBtn.setOnClickListener(v -> doLogin());
        Button webLoginBtn = findViewById(R.id.web_login_btn);
        webLoginBtn.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, WebViewLoginActivity.class)));
    }

    private void doLogin() {
        final String cookie = cookieInput.getText().toString().trim();
        if (cookie.isEmpty()) {
            toast("请先粘贴 Cookie");
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this, "登录", "正在验证 Cookie...", true, false);
        new Thread(() -> {
            try {
                LanzouClient c = session.getClient();
                c.login(cookie);
                session.setCookie(cookie);
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("登录成功");
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
