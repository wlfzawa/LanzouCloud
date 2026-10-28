package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.lanzou.cloud.core.LanzouClient;

import java.util.List;

/** 会话管理：当前账号 Cookie 持久化 + 多账号列表 + 客户端单例 */
public class Session {

    private static final String PREFS = "lanzou";
    private static final String KEY_COOKIE = "cookie";

    private static Session instance;

    public static synchronized Session get(Context ctx) {
        if (instance == null) {
            instance = new Session(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private LanzouClient client;

    private Session(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getCookie() {
        return prefs.getString(KEY_COOKIE, null);
    }

    /** 设置当前账号 Cookie，并同步保存到账号列表 */
    public void setCookie(String cookie) {
        prefs.edit().putString(KEY_COOKIE, cookie).apply();
        String uid = uidOf(cookie);
        if (uid != null) {
            AccountStore.get(app).upsert(cookie, uid);
        }
    }

    public void clearCookie() {
        prefs.edit().remove(KEY_COOKIE).apply();
    }

    public boolean isLoggedIn() {
        String c = getCookie();
        return c != null && !c.trim().isEmpty();
    }

    /** 从 Cookie 字符串里解析 ylogin（uid），失败返回 null */
    public static String uidOf(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("ylogin=")) {
                String v = p.substring("ylogin=".length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 从当前已保存 Cookie 里解析当前账号 uid（ylogin），失败返回 null */
    public String getUid() {
        return uidOf(getCookie());
    }

    // ================================================================ 多账号

    /** 全部已保存账号（最近登录在前） */
    public List<AccountStore.Account> listAccounts() {
        return AccountStore.get(app).list();
    }

    /** 取某个账号保存的 Cookie */
    public String accountCookie(String uid) {
        AccountStore.Account a = AccountStore.get(app).get(uid);
        return a == null ? null : a.cookie;
    }

    /** 删除某个已保存账号 */
    public void removeAccount(String uid) {
        AccountStore.get(app).remove(uid);
    }

    /** 重命名某个已保存账号 */
    public void renameAccount(String uid, String name) {
        AccountStore.get(app).rename(uid, name);
    }

    public synchronized LanzouClient getClient() {
        if (client == null) {
            client = new LanzouClient();
        }
        return client;
    }
}
