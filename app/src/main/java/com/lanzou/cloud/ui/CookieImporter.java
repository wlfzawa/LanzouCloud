package com.lanzou.cloud.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cookie 导入解析器：支持 json / txt。
 *
 * 常见格式：
 *   1. 纯文本：ylogin=123; phpdisk_info=xxx;
 *   2. JSON 对象：{"cookie": "ylogin=...; phpdisk_info=...;", "name": "小号"}
 *   3. JSON 对象（键值对）：{"ylogin": "123", "phpdisk_info": "xxx"}
 *   4. 浏览器插件导出的 JSON 数组：[{"name":"ylogin","value":"123"}, ...]
 *   5. Netscape cookies.txt（Tab 分隔，浏览器 / curl 导出）
 *   6. 多账号 JSON 数组：[{"name":"A","cookie":"..."},{"name":"B","cookie":"..."}]
 */
public final class CookieImporter {

    public static class Item {
        public String name;
        public String cookie;
        /** 来自哪种格式，便于提示 */
        public String format;
    }

    /** 解析失败时的原因（与 items 一起返回） */
    public static class Result {
        public final List<Item> items = new ArrayList<Item>();
        public final List<String> problems = new ArrayList<String>();
    }

    private static final String[] META_KEYS = {
            "name", "value", "domain", "path", "expires", "expirationDate", "httpOnly",
            "secure", "sameSite", "hostOnly", "storeId", "id", "cookie", "remark", "note",
            "comment", "uid", "url", "time", "createTime"
    };

    private CookieImporter() {
    }

    public static Result parse(String raw) {
        Result result = new Result();
        if (raw == null) {
            result.problems.add("文件内容为空");
            return result;
        }
        String text = raw.replace("\uFEFF", "").trim();
        if (text.isEmpty()) {
            result.problems.add("文件内容为空");
            return result;
        }

        char first = text.charAt(0);
        if (first == '{' || first == '[') {
            try {
                JsonElement root = new JsonParser().parse(text);
                parseJson(root, result, null);
            } catch (Exception e) {
                result.problems.add("JSON 解析失败：" + e.getMessage());
            }
        } else {
            parseText(text, result);
        }

        dedupe(result);
        if (result.items.isEmpty() && result.problems.isEmpty()) {
            result.problems.add("没有找到包含 ylogin 与 phpdisk_info 的 Cookie");
        }
        return result;
    }

    // ================================================================ JSON

    private static void parseJson(JsonElement el, Result result, String nameHint) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() == 0) {
                result.problems.add("JSON 数组为空");
                return;
            }
            if (isBrowserCookieList(arr)) {
                String cookie = joinBrowserCookies(arr);
                addItem(result, nameHint, cookie, "浏览器 Cookie JSON");
                return;
            }
            for (int i = 0; i < arr.size(); i++) {
                parseJson(arr.get(i), result, null);
            }
            return;
        }
        if (!el.isJsonObject()) {
            String s = el.isJsonPrimitive() ? el.getAsString() : null;
            if (s != null && s.contains("=")) addItem(result, nameHint, s, "JSON 字符串");
            return;
        }

        JsonObject o = el.getAsJsonObject();
        String name = optString(o, "name");
        if (name == null) name = optString(o, "remark");
        if (name == null) name = nameHint;

        String cookie = optString(o, "cookie");
        if (cookie == null) cookie = optString(o, "cookieString");
        if (cookie != null && cookie.contains("=")) {
            addItem(result, name, cookie, "JSON");
            return;
        }

        // {"accounts":[...]} / {"cookies":[...]} / {"data":[...]}
        for (String key : new String[]{"accounts", "cookies", "list", "data", "items"}) {
            JsonElement sub = o.get(key);
            if (sub != null && sub.isJsonArray()) {
                parseJson(sub, result, name);
                return;
            }
        }

        // 键值对形式：{"ylogin":"123","phpdisk_info":"xxx"}
        if (o.has("ylogin") || o.has("phpdisk_info")) {
            String built = objectToCookie(o);
            if (built != null) addItem(result, name, built, "JSON 键值对");
        } else {
            result.problems.add("JSON 对象里没有 cookie 字段，也没有 ylogin 键");
        }
    }

    private static boolean isBrowserCookieList(JsonArray arr) {
        boolean hasNameValue = false;
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) return false;
            JsonObject o = e.getAsJsonObject();
            if (o.has("name") && o.has("value")) {
                hasNameValue = true;
            } else {
                return false;
            }
        }
        return hasNameValue;
    }

    private static String joinBrowserCookies(JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            String n = optString(o, "name");
            String v = optString(o, "value");
            if (n == null || v == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(n).append('=').append(v);
        }
        return sb.toString();
    }

    private static String objectToCookie(JsonObject o) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            String key = entry.getKey();
            if (isMetaKey(key)) continue;
            JsonElement v = entry.getValue();
            if (v == null || !v.isJsonPrimitive()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(key).append('=').append(v.getAsString());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean isMetaKey(String key) {
        for (String k : META_KEYS) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    // ================================================================ 文本

    private static void parseText(String text, Result result) {
        String[] lines = text.split("\r?\n");
        boolean netscape = false;
        StringBuilder netCookie = new StringBuilder();

        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) continue;

            // Netscape cookies.txt：domain \t flag \t path \t secure \t expiry \t name \t value
            if (!l.startsWith("#")) {
                String[] cols = line.split("\t");
                if (cols.length >= 7) {
                    String domain = cols[0].trim().toLowerCase();
                    String cname = cols[5].trim();
                    String cvalue = cols[6].trim();
                    if (domain.contains("woozooo") || domain.contains("lanzou") || domain.contains("lanzn")) {
                        netscape = true;
                        if (netCookie.length() > 0) netCookie.append("; ");
                        netCookie.append(cname).append('=').append(cvalue);
                    }
                    continue;
                }
            }
            if (l.startsWith("#")) continue;

            String candidate = l;
            if (candidate.toLowerCase().startsWith("cookie:")) {
                candidate = candidate.substring("cookie:".length()).trim();
            }
            if (candidate.toLowerCase().startsWith("set-cookie:")) {
                candidate = candidate.substring("set-cookie:".length()).trim();
            }
            if (candidate.contains("=") && (candidate.contains("ylogin=") || candidate.contains("phpdisk_info="))) {
                addItem(result, null, candidate, "文本");
            }
        }

        if (netscape && netCookie.length() > 0) {
            addItem(result, "cookies.txt", netCookie.toString(), "Netscape cookies.txt");
        }
    }

    // ================================================================ 公共

    private static void addItem(Result result, String name, String cookie, String format) {
        if (cookie == null) return;
        String c = normalize(cookie);
        if (c.isEmpty()) return;
        if (!c.contains("ylogin=")) {
            result.problems.add("缺少 ylogin，无法作为账号使用");
            return;
        }
        if (!c.contains("phpdisk_info=")) {
            result.problems.add("缺少 phpdisk_info（登录票据），请重新复制完整 Cookie");
            return;
        }
        Item it = new Item();
        it.cookie = c;
        it.format = format;
        String uid = uidOf(c);
        it.name = (name != null && !name.trim().isEmpty()) ? name.trim()
                : ("账号 " + (uid == null ? "?" : uid));
        result.items.add(it);
    }

    /** 去掉换行和多余空格，保留 name=value; 结构 */
    private static String normalize(String cookie) {
        String c = cookie.replace("\r", "").replace("\n", " ");
        c = c.replaceAll("\\s*;\\s*", "; ").replaceAll("\\s+", " ").trim();
        if (c.endsWith(";")) c = c.substring(0, c.length() - 1).trim();
        return c;
    }

    private static void dedupe(Result result) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        for (Item it : result.items) {
            String uid = uidOf(it.cookie);
            String key = uid == null ? it.cookie : uid;
            if (!map.containsKey(key)) map.put(key, it);
        }
        result.items.clear();
        result.items.addAll(map.values());
    }

    /** 从 Cookie 里解析 ylogin（账号 UID） */
    private static String uidOf(String cookie) {
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
}
