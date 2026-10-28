package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 多账号存储：保存所有登录过的账号（uid + Cookie），支持列出/切换/删除/重命名。
 * 数据以 JSON 数组存于 SharedPreferences，最近登录的排在最前。
 */
public class AccountStore {

    private static final String PREFS = "lanzou_accounts";
    private static final String KEY_LIST = "list";

    /** 一个已保存的账号 */
    public static class Account {
        public String uid;
        public String name;
        public String cookie;
        public long time;
    }

    private static AccountStore instance;

    public static synchronized AccountStore get(Context ctx) {
        if (instance == null) {
            instance = new AccountStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    private AccountStore(Context app) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private List<Account> load() {
        String json = prefs.getString(KEY_LIST, "[]");
        try {
            Type t = new TypeToken<List<Account>>() {
            }.getType();
            List<Account> list = gson.fromJson(json, t);
            return list != null ? list : new ArrayList<Account>();
        } catch (Exception e) {
            return new ArrayList<Account>();
        }
    }

    private void save(List<Account> list) {
        prefs.edit().putString(KEY_LIST, gson.toJson(list)).apply();
    }

    /** 全部账号，按最近登录时间倒序 */
    public List<Account> list() {
        List<Account> list = load();
        Collections.sort(list, new Comparator<Account>() {
            @Override
            public int compare(Account a, Account b) {
                long x = a == null ? 0 : a.time;
                long y = b == null ? 0 : b.time;
                return x == y ? 0 : (x > y ? -1 : 1);
            }
        });
        return list;
    }

    public Account get(String uid) {
        if (uid == null) return null;
        for (Account a : load()) {
            if (uid.equals(a.uid)) return a;
        }
        return null;
    }

    /**
     * 保存（或更新）一个账号：按 ylogin(uid) 去重，保留已有昵称。
     * 返回该账号对象。
     */
    public Account upsert(String cookie, String uid) {
        List<Account> list = load();
        Account acc = null;
        for (Account a : list) {
            if (uid != null && uid.equals(a.uid)) {
                acc = a;
                break;
            }
        }
        if (acc == null) {
            acc = new Account();
            acc.uid = uid;
            acc.name = "账号 " + (uid == null ? "?" : uid);
            list.add(acc);
        }
        acc.cookie = cookie;
        acc.time = System.currentTimeMillis();
        save(list);
        return acc;
    }

    /** 删除指定账号，返回是否真的删掉了 */
    public boolean remove(String uid) {
        List<Account> list = load();
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            Account a = list.get(i);
            if (uid != null && uid.equals(a.uid)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) save(list);
        return removed;
    }

    /** 重命名账号（空名字恢复默认） */
    public void rename(String uid, String name) {
        if (uid == null) return;
        List<Account> list = load();
        for (Account a : list) {
            if (uid.equals(a.uid)) {
                a.name = (name == null || name.trim().isEmpty())
                        ? ("账号 " + uid) : name.trim();
                break;
            }
        }
        save(list);
    }
}
