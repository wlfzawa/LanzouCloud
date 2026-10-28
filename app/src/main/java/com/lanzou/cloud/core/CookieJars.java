package com.lanzou.cloud.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * 内存 CookieJar：按 host 隔离存储 + 全局 Cookie（跨域发送）。
 *
 * 蓝奏云登录态（ylogin / phpdisk_info / ylogins 等）必须跨域生效：
 * 下载直链会从 pc.woozooo.com → slsstm2.dmpdmp.com → h1053.lanosso.com 多次跳转，
 * 若按域隔离则中间跳转丢失登录 Cookie，导致 CDN 返回挑战页/HTML。
 * 因此登录 Cookie 统一走「全局」通道（对齐 requests 的跨域行为）。
 *
 * 但 CDN 的防护 Cookie（acw_tc / cdn_sec_tc / acw_sc__v2）**绝不能全局发送**：
 * 它们是各 CDN 节点自己下发的会话票据，把 PC 站的旧值带到 CDN 域会让
 * ESA 一直认为会话非法，acw_sc__v2 算对了也永远过不了挑战。
 */
class InMemoryCookieJar implements CookieJar {

    /** 只按域发送、不进入全局通道的防护 Cookie */
    private static final Set<String> LOCAL_ONLY = new HashSet<>(Arrays.asList(
            "acw_tc", "cdn_sec_tc", "acw_sc__v2", "codelen", "pc_ad1"
    ));

    private final HashMap<String, List<Cookie>> store = new HashMap<>();
    private final LinkedHashMap<String, String> global = new LinkedHashMap<>();

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String key = url.host();
        List<Cookie> list = store.get(key);
        if (list == null) {
            list = new ArrayList<>();
            store.put(key, list);
        }
        for (Cookie c : cookies) {
            removeByName(list, c.name());
            list.add(c);
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        // 1) 域隔离 Cookie 优先（含服务端下发的新 acw_tc / acw_sc__v2）
        Set<String> localNames = new HashSet<>();
        for (List<Cookie> list : store.values()) {
            for (Cookie cookie : list) {
                if (cookie.matches(url)) {
                    result.add(cookie);
                    localNames.add(cookie.name());
                }
            }
        }
        // 2) 全局登录 Cookie：同名时让位给域隔离值，避免重复发送同名 Cookie。
        //    服务端只取第一个值，旧 acw_tc 覆盖新 acw_tc 会导致挑战永远过不了。
        for (Map.Entry<String, String> e : global.entrySet()) {
            if (localNames.contains(e.getKey())) continue;
            result.add(new Cookie.Builder()
                    .name(e.getKey())
                    .value(e.getValue())
                    .hostOnlyDomain(url.host())
                    .path("/")
                    .build());
        }
        return result;
    }

    /** 手动注入域隔离 cookie（domain 可传 `woozooo.com` 或 `.woozooo.com`） */
    void addCookie(String domain, String name, String value) {
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        Cookie cookie = new Cookie.Builder()
                .name(name)
                .value(value)
                .domain(d)
                .path("/")
                .build();
        List<Cookie> list = store.get(d);
        if (list == null) {
            list = new ArrayList<>();
            store.put(d, list);
        }
        removeByName(list, name);
        list.add(cookie);
    }

    /** 手动注入 host-only cookie（等价浏览器 document.cookie，不发送到子域） */
    void addHostCookie(String host, String name, String value) {
        Cookie cookie = new Cookie.Builder()
                .name(name)
                .value(value)
                .hostOnlyDomain(host)
                .path("/")
                .build();
        List<Cookie> list = store.get(host);
        if (list == null) {
            list = new ArrayList<>();
            store.put(host, list);
        }
        removeByName(list, name);
        list.add(cookie);
    }

    /** 手动注入全局 cookie（对所有 host 都发送，用于登录态跨域） */
    void addGlobalCookie(String name, String value) {
        if (LOCAL_ONLY.contains(name)) return;
        global.put(name, value);
    }

    /**
     * 清掉某个 host 的防护 Cookie，让 ESA 重新下发一套会话票据。
     * 挑战/验证反复失败时，「旧 acw_tc + 新 acw_sc__v2」的组合会一直被判非法，
     * 必须换一个全新的会话才有机会通过。
     */
    void clearProtection(String host) {
        List<Cookie> list = store.get(host);
        if (list == null) return;
        Iterator<Cookie> it = list.iterator();
        while (it.hasNext()) {
            if (LOCAL_ONLY.contains(it.next().name())) it.remove();
        }
    }

    /** 清空全部 cookie（切换账号时使用） */
    void clear() {
        store.clear();
        global.clear();
    }

    private static void removeByName(List<Cookie> list, String name) {
        Iterator<Cookie> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().name().equals(name)) {
                it.remove();
            }
        }
    }
}
