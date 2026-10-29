package com.lanzou.cloud.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 链接历史记录：打开/下载过的分享链接持久保存，进程被杀、重启后依然在。
 * 同一个链接只保留一条，重复打开会把它移到最前面。
 */
public class HistoryStore {

    private static final String PREFS = "lanzou_link_history";
    private static final String KEY_LIST = "list";
    private static final int MAX = 100;

    public static final String TYPE_FILE = "file";
    public static final String TYPE_FOLDER = "folder";

    public static class Entry {
        public String url;
        public String pwd;
        public String name;
        public String type;
        public long time;
    }

    private static HistoryStore instance;

    public static synchronized HistoryStore get(Context ctx) {
        if (instance == null) {
            instance = new HistoryStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    private HistoryStore(Context app) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private List<Entry> load() {
        String json = prefs.getString(KEY_LIST, "[]");
        try {
            Type t = new TypeToken<List<Entry>>() {
            }.getType();
            List<Entry> list = gson.fromJson(json, t);
            return list == null ? new ArrayList<Entry>() : list;
        } catch (Exception e) {
            return new ArrayList<Entry>();
        }
    }

    private void save(List<Entry> list) {
        prefs.edit().putString(KEY_LIST, gson.toJson(list)).apply();
    }

    /** 记录一次打开/下载（name 可为空，之后可补） */
    public void add(String url, String pwd, String name, String type) {
        if (url == null || url.trim().isEmpty()) return;
        List<Entry> list = load();
        Entry existing = null;
        for (Entry e : list) {
            if (url.equals(e.url)) {
                existing = e;
                list.remove(e);
                break;
            }
        }
        Entry e = existing != null ? existing : new Entry();
        e.url = url;
        if (pwd != null && !pwd.isEmpty()) e.pwd = pwd;
        if (name != null && !name.trim().isEmpty()) e.name = name.trim();
        e.type = type;
        e.time = System.currentTimeMillis();
        list.add(0, e);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        save(list);
    }

    /** 补全文件名（解析到真名后调用） */
    public void updateName(String url, String name) {
        if (url == null || name == null || name.trim().isEmpty()) return;
        List<Entry> list = load();
        boolean changed = false;
        for (Entry e : list) {
            if (url.equals(e.url)) {
                e.name = name.trim();
                changed = true;
                break;
            }
        }
        if (changed) save(list);
    }

    public List<Entry> list() {
        return load();
    }

    public void remove(Entry entry) {
        if (entry == null) return;
        List<Entry> list = load();
        for (int i = 0; i < list.size(); i++) {
            if (entry.url != null && entry.url.equals(list.get(i).url)) {
                list.remove(i);
                break;
            }
        }
        save(list);
    }

    public void clear() {
        save(new ArrayList<Entry>());
    }

    public int size() {
        return load().size();
    }
}
