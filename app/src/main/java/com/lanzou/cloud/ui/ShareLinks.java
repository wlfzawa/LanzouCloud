package com.lanzou.cloud.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一段文本（剪贴板内容、聊天记录）里识别蓝奏云分享链接与提取码。
 *
 * 支持：
 *   https://wwbkc.lanzn.com/itNN74dsdknc        文件
 *   https://ww0.lanzout.com/b00eexd03e          文件夹
 *   ww0.lanzout.com/b00eexd03e 密码: 00          无协议头 + 提取码
 */
public final class ShareLinks {

    /** 识别结果 */
    public static final class Link {
        /** 规范化后的完整链接（带 https://） */
        public final String url;
        /** 提取码，可能为 null */
        public final String pwd;
        /** 是否文件夹分享（/bXXXX） */
        public final boolean folder;

        Link(String url, String pwd, boolean folder) {
            this.url = url;
            this.pwd = pwd;
            this.folder = folder;
        }
    }

    /** 域名/路径片段：允许 .lanzou*、.lanzn、.woozooo 等域名，必须带路径 */
    private static final Pattern URL_TOKEN = Pattern.compile(
            "(?i)(?:https?://)?[a-z0-9][a-z0-9._-]*\\.[a-z]{2,}(?::\\d+)?/[^\\s\"'<>，。；、）】\\]]*");

    /** 分享码：i / b 开头，后接至少 5 位字母数字 */
    private static final Pattern CODE = Pattern.compile("^/([ib][A-Za-z0-9]{5,})/?$");

    private static final Pattern PWD = Pattern.compile(
            "(?i)(?:提取码|访问码|密码|口令|pwd|password|code)\\s*[:：=]?\\s*[\\\"'（(【\\[]?\\s*([A-Za-z0-9]{2,12})");

    private static final Pattern PWD_QUERY = Pattern.compile("(?i)[?&](?:pwd|password|pass|code)=([A-Za-z0-9]{2,12})");

    private ShareLinks() {
    }

    /** 解析文本，找不到返回 null */
    public static Link parse(String text) {
        if (text == null) return null;
        String body = text.trim();
        if (body.isEmpty()) return null;

        Matcher m = URL_TOKEN.matcher(body);
        while (m.find()) {
            String token = trimTail(m.group());
            String url = normalize(token);
            if (url == null) continue;
            String path = pathOf(url);
            if (path == null) continue;
            Matcher cm = CODE.matcher(path);
            if (!cm.matches()) continue;

            String code = cm.group(1);
            boolean folder = code.charAt(0) == 'b' || code.charAt(0) == 'B';
            String pwd = queryPwd(url);
            if (pwd == null) pwd = textPwd(body);
            return new Link(url, pwd, folder);
        }
        return null;
    }

    /** 去掉链接末尾可能粘上的中文标点/引号 */
    private static String trimTail(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == '.' || c == ';' || c == ':' || c == ')' || c == ']'
                    || c == '"' || c == '\'' || c == '，' || c == '。' || c == '；'
                    || c == '、' || c == '）' || c == '】' || c == '》') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** 补全协议头并校验是否蓝奏云域名；不合法返回 null */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.toLowerCase().startsWith("http://") && !s.toLowerCase().startsWith("https://")) {
            s = "https://" + s;
        }
        String host = hostOf(s);
        if (host == null) return null;
        host = host.toLowerCase();
        if (!(host.contains("lanzou") || host.contains("lanzn") || host.contains("woozooo"))) {
            return null;
        }
        return s;
    }

    /** 判断一个字符串是否是（或包含）可识别的蓝奏云分享链接 */
    public static boolean isShareUrl(String text) {
        return parse(text) != null;
    }

    public static String pathOf(String url) {
        try {
            int i = url.indexOf("://");
            String rest = i >= 0 ? url.substring(i + 3) : url;
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            String path = rest.substring(slash);
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    public static String hostOf(String url) {
        try {
            int i = url.indexOf("://");
            String rest = i >= 0 ? url.substring(i + 3) : url;
            int slash = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) hostPort = hostPort.substring(at + 1);
            int colon = hostPort.indexOf(':');
            return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        } catch (Exception e) {
            return null;
        }
    }

    /** 链接里自带的 ?pwd=xxxx */
    private static String queryPwd(String url) {
        Matcher m = PWD_QUERY.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 从整段文字里找「提取码: xx」 */
    private static String textPwd(String text) {
        Matcher m = PWD.matcher(text);
        if (m.find()) {
            String v = m.group(1);
            // 排除把链接里的域名片段误当密码的情况
            if (v != null && !"http".equalsIgnoreCase(v) && !"https".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }

    /** 分享码（iXXXX / bXXXX），取不到返回 null */
    public static String codeOf(String url) {
        String path = pathOf(url);
        if (path == null) return null;
        Matcher cm = CODE.matcher(path);
        return cm.matches() ? cm.group(1) : null;
    }
}
