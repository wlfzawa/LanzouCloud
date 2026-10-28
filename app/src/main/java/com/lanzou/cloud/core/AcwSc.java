package com.lanzou.cloud.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝奏云 / 阿里云 ESA 的 acw_sc__v2 JS 挑战。
 *
 * 挑战页正文形如 {@code <html><script>var arg1='...';(function(a,c){...}，
 * arg1 的位置随节点不同而变化（有时在页面很靠后），不能只看开头几千字符。
 *
 * 通过挑战后，CDN 可能再返回一个「验证并下载」中间页（含 ajax.php），
 * 需要 POST file / sign 参数换取真实文件地址，这里一并提供识别与解析。
 */
public final class AcwSc {

    private static final int[] BOX = {
            15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
            39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36
    };

    private static final String KEY = "3000176000856006061501533003690027800375";

    /** 兼容 arg1 = '...' / arg1="..." / arg1 = '...' 等写法 */
    private static final Pattern ARG1 = Pattern.compile("arg1\\s*=\\s*['\"]([0-9A-Za-z]+)['\"]");

    /** 页面标题 */
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** JS 跳转：location.href = '...' / location.replace('...') / window.location='...' */
    private static final Pattern JS_LOCATION = Pattern.compile(
            "(?:location\\s*\\.\\s*(?:href\\s*=\\s*|replace\\s*\\(\\s*)|window\\s*\\.\\s*location\\s*=\\s*)"
                    + "['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    /** meta refresh */
    private static final Pattern META_REFRESH = Pattern.compile(
            "<meta[^>]+http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]*content\\s*=\\s*['\"][^'\"]*url\\s*=\\s*([^'\">\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private AcwSc() {
    }

    /**
     * 判断文本是否为 acw_sc__v2 挑战页。
     * 全页扫描：不同 CDN 节点会把 arg1 放在不同位置，只扫开头会漏判，
     * 漏判的后果就是把挑战页当文件存下来（用户看到「下载的是 html」）。
     */
    public static boolean isChallenge(String text) {
        if (text == null || text.isEmpty() || text.length() > 512 * 1024) return false;
        String lower = text.toLowerCase();
        if (!lower.contains("<script")) return false;
        // 分享页 / 中间页不含 arg1 挑战逻辑，排除误判
        if (lower.contains("isngis") || lower.contains("down_r")) return false;
        Matcher m = ARG1.matcher(text);
        if (!m.find()) return false;
        int len = m.group(1).length();
        return len >= 32 && len <= 64;
    }

    /** 判断是否为「验证并下载」中间页（体积小、含 ajax.php 与 down_r 逻辑） */
    public static boolean isVerifyPage(String text) {
        if (text == null || text.length() > 512 * 1024) return false;
        if (!text.contains("ajax.php")) return false;
        if (text.contains("down_r") || text.contains("验证并下载")) return true;
        // 部分节点换了文案，但一定同时带 file / sign 两个参数
        return text.contains("'file'") && text.contains("'sign'");
    }

    /** 从中间页 HTML 里取 JS 对象的字面量参数，如 'file':'xxx' */
    public static String grabParam(String html, String name) {
        if (html == null || name == null) return null;
        Matcher m = Pattern.compile("['\"]" + name + "['\"]\\s*:\\s*['\"]([^'\"]*)['\"]")
                .matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 页面标题（用于错误提示，让用户知道服务端到底返回了什么） */
    public static String pageTitle(String html) {
        if (html == null) return "";
        Matcher m = TITLE.matcher(html);
        if (!m.find()) return "";
        return m.group(1).replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    /** 正文摘要（去标签），用于没有 title 的错误页 */
    public static String snippet(String html, int max) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > max) s = s.substring(0, max);
        return s;
    }

    /** 从页面里找 JS / meta 跳转地址 */
    public static String findRedirect(String html) {
        if (html == null) return null;
        Matcher m = JS_LOCATION.matcher(html);
        if (m.find()) return m.group(1);
        m = META_REFRESH.matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 二进制头部是否其实是 HTML 页面（CDN 常把错误页/验证页标成 octet-stream） */
    public static boolean looksHtml(byte[] buf, int len) {
        if (buf == null || len <= 0) return false;
        int n = Math.min(len, 2048);
        String s = new String(buf, 0, n, java.nio.charset.Charset.forName("ISO-8859-1"))
                .toLowerCase();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            // 跳过空白与 UTF-8 BOM 在 Latin-1 下的残片（ï»¿）
            if (Character.isWhitespace(c) || c == '\u00ef' || c == '\u00bb' || c == '\u00bf') {
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(i);
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.contains("<html");
    }

    /** 从挑战页 HTML 计算 acw_sc__v2 cookie 值 */
    public static String calc(String html) {
        if (html == null) return "";
        Matcher m = ARG1.matcher(html);
        if (!m.find()) return "";
        String arg1 = m.group(1);
        if (arg1.length() < 40) return "";
        String src = unsbox(arg1);
        StringBuilder sb = new StringBuilder();
        try {
            int i = 0;
            while (i + 2 <= src.length() && i + 2 <= KEY.length()) {
                int x = Integer.parseInt(src.substring(i, i + 2), 16)
                        ^ Integer.parseInt(KEY.substring(i, i + 2), 16);
                String h = Integer.toHexString(x);
                if (h.length() < 2) sb.append('0');
                sb.append(h);
                i += 2;
            }
        } catch (NumberFormatException e) {
            return "";
        }
        return sb.toString();
    }

    private static String unsbox(String s) {
        char[] out = new char[BOX.length];
        for (int i = 0; i < s.length() && i < BOX.length; i++) {
            for (int j = 0; j < BOX.length; j++) {
                if (BOX[j] == i + 1) {
                    out[j] = s.charAt(i);
                    break;
                }
            }
        }
        return new String(out);
    }
}
