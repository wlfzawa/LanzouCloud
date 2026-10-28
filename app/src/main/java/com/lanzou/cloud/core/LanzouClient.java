package com.lanzou.cloud.core;

import android.net.Uri;
import android.os.Build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * 蓝奏云网盘客户端（纯 Java，兼容 Android 4.4+）。
 *
 * 直接对接 2026 年现行 Web 接口，只依赖 OkHttp 3.12 + Gson。
 * 所有失败统一抛 {@link LanzouException}。
 */
public class LanzouClient {

    public static final String HOST = "https://pc.woozooo.com";
    public static final String URL_DOUPLOAD = HOST + "/doupload.php";
    public static final String URL_UPLOAD = HOST + "/html5up.php";
    public static final String URL_AJAXFILE = "https://apifile.woozooo.com/ajaxfile.php";

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Set<String> VALID_SUFFIX = new HashSet<>(Arrays.asList(
            "ppt", "xapk", "ke", "azw", "cpk", "gho", "dwg", "db", "docx", "deb", "e", "ttf", "xls",
            "bat", "crx", "rpm", "txf", "pdf", "apk", "ipa", "txt", "mobi", "osk", "dmg", "rp", "osz",
            "jar", "ttc", "z", "w3x", "xlsx", "cetrainer", "ct", "rar", "mp3", "pptx", "mobileconfig",
            "epub", "imazingapp", "doc", "iso", "img", "appimage", "7z", "rplib", "lolgezi", "exe",
            "azw3", "zip", "conf", "tar", "dll", "flac", "xpa", "lua", "cad", "hwt", "accdb", "ce",
            "xmind", "enc", "bds", "bdi", "ssf", "it", "gz"
    ));

    private final InMemoryCookieJar cookieJar = new InMemoryCookieJar();
    private final OkHttpClient http;
    private final OkHttpClient uploadHttp;
    private final OkHttpClient downloadHttp;
    private final long timeout;

    private String uid;

    public LanzouClient() {
        this(30);
    }

    public LanzouClient(long timeout) {
        this.timeout = timeout;
        this.http = newClient(timeout);
        this.uploadHttp = newClient(Math.max(timeout, 3600));
        this.downloadHttp = newClient(Math.max(timeout, 600));
    }

    private OkHttpClient newClient(long t) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(t, TimeUnit.SECONDS)
                .readTimeout(t, TimeUnit.SECONDS)
                .writeTimeout(t, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        applyTls(b);
        return b.build();
    }

    /**
     * 按系统版本选择 TLS 实现：
     *   API 9~15  系统 TLS 栈不支持 TLS 1.2  → wolfSSL 承载（自研 JNI）
     *   API 16~20 系统支持但默认未启用        → 强制启用 TLSv1.2
     *   API 21+   原生支持，保持默认
     */
    private static void applyTls(OkHttpClient.Builder b) {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 21) return;

        if (sdk < 16 && WolfTls.available()) {
            try {
                WolfSSLSocketFactory f = new WolfSSLSocketFactory();
                b.sslSocketFactory(f, f.trustManager());
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager tm = null;
            TrustManager[] tms = tmf.getTrustManagers();
            for (int i = 0; i < tms.length; i++) {
                if (tms[i] instanceof X509TrustManager) {
                    tm = (X509TrustManager) tms[i];
                    break;
                }
            }
            if (tm != null) b.sslSocketFactory(new TLSSocketFactory(), tm);
        } catch (Throwable ignored) {
        }
    }

    public String getUid() {
        return uid;
    }

    // ================================================================ 登录

    public void login(String cookie) throws LanzouException {
        Map<String, String> dict = parseCookie(cookie);
        uid = dict.get("ylogin");
        for (Map.Entry<String, String> e : dict.entrySet()) {
            String name = e.getKey();
            // CDN/ESA 防护 Cookie 只能按域发送：PC 站的旧 acw_tc 若被带到 CDN 节点，
            // 会让 ESA 一直认为会话非法，acw_sc__v2 算对了也过不了挑战。
            if ("acw_tc".equals(name) || "cdn_sec_tc".equals(name)
                    || "acw_sc__v2".equals(name) || "codelen".equals(name)
                    || "pc_ad1".equals(name)) {
                cookieJar.addCookie("woozooo.com", name, e.getValue());
            } else {
                cookieJar.addGlobalCookie(name, e.getValue());
            }
        }
        checkLogin();
    }

    /** 清空全部 Cookie 与登录态（切换账号前调用） */
    public void reset() {
        cookieJar.clear();
        uid = null;
    }

    public void checkLogin() throws LanzouException {
        Map<String, String> data = new HashMap<>();
        data.put("task", "5");
        data.put("folder_id", "-1");
        data.put("pg", "1");
        JsonObject d = api(data);
        if (!"1".equals(getStr(d, "zt"))) {
            throw new LanzouException(LanzouException.ID_ERROR, "cookie 已失效，请重新从浏览器复制");
        }
    }

    // ================================================================ 目录

    public Models.DirListing listDir(long folderId) throws LanzouException {
        List<Models.FolderItem> folders = new ArrayList<>();
        List<Models.FileItem> files = new ArrayList<>();
        int page = 1;
        while (page <= 200) {
            Map<String, String> data = new HashMap<>();
            data.put("task", "5");
            data.put("folder_id", String.valueOf(folderId));
            data.put("pg", String.valueOf(page));
            JsonObject d = api(data);
            if (!"1".equals(getStr(d, "zt"))) {
                throw new LanzouException(LanzouException.FAILED, "获取列表失败: " + getStr(d, "info"));
            }
            JsonArray batch = d.getAsJsonArray("text");
            if (batch == null || batch.size() == 0) break;
            for (int i = 0; i < batch.size(); i++) {
                if (!batch.get(i).isJsonObject()) continue;
                JsonObject it = batch.get(i).getAsJsonObject();
                if (it.has("id")) {
                    files.add(normFile(it));
                } else if (it.has("folder_id")) {
                    folders.add(normFolder(it));
                }
            }
            page++;
        }
        if (folderId == -1L) {
            Set<Long> seen = new HashSet<>();
            for (Models.FolderItem f : folders) seen.add(f.id);
            for (Models.FolderItem f : rootFolders()) {
                if (!seen.contains(f.id)) folders.add(f);
            }
        }
        return new Models.DirListing(folders, files);
    }

    public List<Models.FolderItem> rootFolders() throws LanzouException {
        Map<String, String> data = new HashMap<>();
        data.put("task", "19");
        data.put("folder_id", "-1");
        JsonObject d = api(data);
        List<Models.FolderItem> out = new ArrayList<>();
        JsonArray info = d.getAsJsonArray("info");
        if (info != null) {
            for (int i = 0; i < info.size(); i++) {
                if (!info.get(i).isJsonObject()) continue;
                JsonObject it = info.get(i).getAsJsonObject();
                String fid = getStr(it, "folder_id");
                if (!fid.isEmpty()) {
                    out.add(new Models.FolderItem(parseLong(fid), getStr(it, "folder_name"), false, ""));
                }
            }
        }
        return out;
    }

    private static Models.FileItem normFile(JsonObject it) {
        String nameAll = getStr(it, "name_all");
        String name = nameAll.isEmpty() ? getStr(it, "name") : nameAll;
        String type = "";
        if (!nameAll.isEmpty() && nameAll.contains(".")) {
            type = nameAll.substring(nameAll.lastIndexOf('.') + 1).toLowerCase();
        }
        return new Models.FileItem(
                parseLong(getStr(it, "id")),
                name.replace("&amp;", "&"),
                getStr(it, "size").replace(",", ""),
                getStr(it, "time"),
                type,
                parseInt(getStr(it, "downs")),
                "1".equals(getStr(it, "onof")),
                getStr(it, "desc")
        );
    }

    private static Models.FolderItem normFolder(JsonObject it) {
        String name = getStr(it, "folder_name");
        if (name.isEmpty()) name = getStr(it, "name");
        return new Models.FolderItem(
                parseLong(getStr(it, "folder_id")),
                name,
                "1".equals(getStr(it, "offon")),
                getStr(it, "folder_description")
        );
    }

    // ================================================================ 文件夹

    public long mkdir(long parentId, String name, String desc) throws LanzouException {
        String n = safeName(name).replace(" ", "_");
        if (n.isEmpty()) {
            throw new LanzouException(LanzouException.MKDIR_ERROR, "文件夹名过滤后为空");
        }
        Map<String, String> data = new HashMap<>();
        data.put("task", "2");
        data.put("parent_id", String.valueOf(parentId <= 0 ? -1 : parentId));
        data.put("folder_name", n);
        data.put("folder_description", desc == null ? "" : desc);
        JsonObject d = api(data);
        if (!"1".equals(getStr(d, "zt"))) {
            throw new LanzouException(LanzouException.MKDIR_ERROR, "创建文件夹失败: " + getStr(d, "info"));
        }
        return parseLong(getStr(d, "text"));
    }

    // ================================================================ 分享

    public Models.ShareInfo share(long fid, boolean isFile) throws LanzouException {
        Map<String, String> data = new HashMap<>();
        if (isFile) {
            data.put("task", "22");
            data.put("file_id", String.valueOf(fid));
        } else {
            data.put("task", "18");
            data.put("folder_id", String.valueOf(fid));
        }
        JsonObject d = api(data);
        JsonObject info = d.getAsJsonObject("info");
        if (info == null) {
            throw new LanzouException(LanzouException.ID_ERROR, "取不到分享信息，id 无效或类型不对");
        }
        String url, name, desc;
        if (isFile) {
            url = getStr(info, "is_newd") + "/" + getStr(info, "f_id");
            name = getStr(info, "name");
            desc = getStr(info, "pwd");
            try {
                Map<String, String> d12 = new HashMap<>();
                d12.put("task", "12");
                d12.put("file_id", String.valueOf(fid));
                JsonObject r12 = api(d12);
                if (!getStr(r12, "text").isEmpty()) name = getStr(r12, "text");
                if (!getStr(r12, "info").isEmpty()) desc = getStr(r12, "info");
            } catch (LanzouException ignored) {
            }
        } else {
            url = getStr(info, "new_url");
            name = getStr(info, "name");
            desc = getStr(info, "des");
        }
        if (url.isEmpty()) {
            throw new LanzouException(LanzouException.FAILED, "服务端没有返回分享链接");
        }
        String pwd = "1".equals(getStr(info, "onof")) ? getStr(info, "pwd") : "";

        // 新版分享页标题只有「文件」，接口给的 name 也常缺扩展名，
        // 这里拉一次分享页，用页面标题和类型图标把名字补全（文件都不大，代价很低）
        if (!name.isEmpty() && !hasExtension(name)) {
            try {
                String page = getHtml(url, null);
                String pn = pageName(page);
                if (pn != null && hasExtension(pn)) {
                    name = pn;
                } else {
                    String ext = pageFileType(page);
                    if (!ext.isEmpty()) name = name + "." + ext;
                }
            } catch (LanzouException ignored) {
            }
        }
        return new Models.ShareInfo(name, url, pwd, desc);
    }

    // ================================================================ 文件夹分享

    /** 文件夹分享的会话参数：列表页只解析一次，翻页与下载复用 */
    public static final class FolderRef {
        public final String pageUrl;
        public final String baseUrl;
        public final String fid;
        public final String uid;
        public final String puid;
        public final String t;
        public final String k;
        public final String name;
        public final String pwd;

        FolderRef(String pageUrl, String baseUrl, String fid, String uid, String puid,
                  String t, String k, String name, String pwd) {
            this.pageUrl = pageUrl;
            this.baseUrl = baseUrl;
            this.fid = fid;
            this.uid = uid;
            this.puid = puid;
            this.t = t;
            this.k = k;
            this.name = name;
            this.pwd = pwd;
        }
    }

    /** 是否为文件夹分享链接（/bXXXXXX，带不带 /s/ 前缀都算） */
    public static boolean isFolderShareUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String path;
        try {
            path = Uri.parse(url.trim()).getPath();
        } catch (Exception e) {
            return false;
        }
        if (path == null) return false;
        String p = path.replaceAll("^/+", "").replaceAll("/+$", "");
        return Pattern.compile("(?:s/)?b[A-Za-z0-9]{6,}").matcher(p).matches();
    }

    /**
     * 打开文件夹分享链接：解析页面参数（fid/uid/puid/t/k）并确认密码可用。
     * 真正取文件列表请调用 {@link #listFolder(FolderRef, int)}。
     */
    public FolderRef openFolder(String url, String pwd) throws LanzouException {
        String clean = url == null ? "" : url.trim();
        if (clean.isEmpty()) {
            throw new LanzouException(LanzouException.URL_INVALID, "链接为空");
        }
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        String html = getHtml(clean, null);

        // 少数情况下页面仍是旧版「先提交密码」结构，这里补一次表单提交
        String fid = grab(html, "'fid'\\s*:\\s*'?(\\d+)");
        if (fid.isEmpty() && html.contains("id=\"pwd\"") && pwd != null && !pwd.isEmpty()) {
            Map<String, String> pd = new HashMap<>();
            pd.put("pwd", pwd);
            String html2 = postForm(clean, pd, clean);
            if (!html2.trim().isEmpty()) html = html2;
            fid = grab(html, "'fid'\\s*:\\s*'?(\\d+)");
        }
        if (fid.isEmpty()) {
            throw new LanzouException(LanzouException.URL_INVALID,
                    "解析文件夹失败：链接可能已失效，或该链接不是文件夹分享");
        }

        Uri u = Uri.parse(clean);
        String base = (u.getScheme() == null ? "https" : u.getScheme()) + "://" + u.getHost();
        String uid = grab(html, "'uid'\\s*:\\s*'?(\\d+)");
        String puid = grab(html, "'puid'\\s*:\\s*'([^']*)'");
        // 校时/校验变量名每次请求都会随机生成（如 ibhocx/_hg0eq → ibjtip/_hi5ki），
        // 只能按值的形态识别：10 位时间戳、32 位十六进制 md5
        String t = grab(html, "var\\s+\\w+\\s*=\\s*'(\\d{10})'");
        String k = grab(html, "var\\s+\\w+\\s*=\\s*'([0-9a-fA-F]{32})'");
        if (t.isEmpty()) {
            // 少数模板直接把 t/k 写在 data 里
            t = grab(html, "'t'\\s*:\\s*'?(\\d{10})");
        }
        if (k.isEmpty()) {
            k = grab(html, "'k'\\s*:\\s*'([0-9a-fA-F]{32})");
        }
        String name = pageName(html);
        if (!isUsableFileName(name)) name = "文件夹分享";
        return new FolderRef(clean, base, fid, uid, puid,
                t.isEmpty() ? "" : t, k.isEmpty() ? "" : k, name, pwd == null ? "" : pwd);
    }

    /** 取文件夹分享的一页（每页最多 50 条） */
    public Models.FolderShare listFolder(FolderRef ref, int page) throws LanzouException {
        if (ref == null) {
            throw new LanzouException(LanzouException.URL_INVALID, "文件夹尚未打开");
        }
        Map<String, String> data = new HashMap<>();
        data.put("lx", "2");
        data.put("fid", ref.fid);
        data.put("uid", ref.uid);
        data.put("puid", ref.puid);
        data.put("pg", String.valueOf(page <= 0 ? 1 : page));
        data.put("rep", "0");
        data.put("t", ref.t);
        data.put("k", ref.k);
        data.put("up", "1");
        data.put("ls", "1");
        data.put("pwd", ref.pwd);

        String api = ref.baseUrl + "/filemoreajax.php?file=" + ref.fid;
        JsonObject d = null;
        // 服务端对翻页有频率限制，过快会回 zt=4「请刷新，重试」或直接返回空响应，
        // 这里退避 1.5 秒重试一次（连续快速翻页时最容易触发）
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                d = postFormJson(api, data, ref.pageUrl);
            } catch (LanzouException e) {
                d = null;
            }
            if (d != null && !"4".equals(getStr(d, "zt"))) break;
            d = null;
            if (attempt == 0) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (d == null) {
            throw new LanzouException(LanzouException.FAILED,
                    "请求过于频繁，请稍等几秒再点「加载更多」");
        }
        String zt = getStr(d, "zt");
        String info = getStr(d, "info");


        if ("3".equals(zt)) {
            throw new LanzouException(LanzouException.PASSWORD_ERROR,
                    info.isEmpty() ? "提取码不正确" : info);
        }
        if ("2".equals(zt)) {
            return new Models.FolderShare(ref.name, ref.baseUrl, ref.pwd,
                    new ArrayList<Models.ShareFile>(), 1, false);
        }
        if (!"1".equals(zt)) {
            throw new LanzouException(LanzouException.FAILED,
                    "取文件列表失败" + (info.isEmpty() ? "" : "：" + info));
        }

        List<Models.ShareFile> files = new ArrayList<>();
        JsonArray arr = d.getAsJsonArray("text");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject it = arr.get(i).getAsJsonObject();
                if ("1".equals(getStr(it, "t"))) continue; // 推广位
                String id = getStr(it, "id");
                int q = id.indexOf('?');
                if (q > 0) id = id.substring(0, q);
                if (id.length() < 2 || id.charAt(0) != 'i') continue;
                String name = getStr(it, "name_all");
                String icon = getStr(it, "icon");
                if (!name.isEmpty() && !hasExtension(name) && !icon.isEmpty()) {
                    name = name + "." + icon;
                }
                files.add(new Models.ShareFile(id, name, getStr(it, "size"),
                        getStr(it, "time"), icon));
            }
        }
        boolean hasMore = arr != null && arr.size() >= 50;
        return new Models.FolderShare(ref.name, ref.baseUrl, ref.pwd, files,
                page <= 0 ? 1 : page, hasMore);
    }

    // ================================================================ 上传

    public Models.UploadResult upload(final InputStream in, final String fileName, final long size,
                                      long folderId, final ProgressListener listener)
            throws LanzouException {
        String suffix = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!VALID_SUFFIX.contains(suffix)) {
            throw new LanzouException(LanzouException.OFFICIAL_LIMITED,
                    "后缀 \"" + suffix + "\" 不在官方白名单，请先压缩成 zip");
        }

        RequestBody fileBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                byte[] buf = new byte[64 * 1024];
                int n;
                long done = 0;
                while ((n = in.read(buf)) != -1) {
                    if (n == 0) continue;
                    sink.write(buf, 0, n);
                    done += n;
                    if (listener != null) listener.onProgress(fileName, size, done);
                }
            }
        };

        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("task", "1")
                .addFormDataPart("vie", "2")
                .addFormDataPart("ve", "2")
                .addFormDataPart("folder_id_bb_n", String.valueOf(folderId <= 0 ? -1 : folderId))
                .addFormDataPart("name", fileName)
                .addFormDataPart("upload_file", fileName, fileBody)
                .build();

        Request req = new Request.Builder().url(URL_UPLOAD).post(body)
                .header("User-Agent", UA)
                .header("Referer", HOST + "/mydisk.php")
                .build();

        Response resp = null;
        try {
            resp = uploadHttp.newCall(req).execute();
            String bodyStr = resp.body() == null ? "" : resp.body().string();
            if (bodyStr.trim().isEmpty()) {
                throw new LanzouException(LanzouException.FAILED, "上传接口返回空响应");
            }
            JsonObject d = new JsonParser().parse(bodyStr).getAsJsonObject();
            if (!"1".equals(getStr(d, "zt"))) {
                throw new LanzouException(LanzouException.FAILED, "上传失败: " + getStr(d, "info"));
            }
            JsonArray text = d.getAsJsonArray("text");
            JsonObject item = (text != null && text.size() > 0 && text.get(0).isJsonObject())
                    ? text.get(0).getAsJsonObject() : new JsonObject();
            long id = parseLong(getStr(item, "id"));
            String name = getStr(item, "name_all");
            if (name.isEmpty()) name = fileName;
            String sz = getStr(item, "size");
            if (sz.isEmpty()) sz = formatSize(size);
            String fId = getStr(item, "f_id");
            String shareUrl = fId.isEmpty() ? "" : getStr(item, "is_newd") + "/" + fId;
            return new Models.UploadResult(id, name, sz, shareUrl);
        } catch (IOException e) {
            throw new LanzouException(LanzouException.NETWORK_ERROR, "上传失败: " + e.getMessage());
        } catch (LanzouException e) {
            throw e;
        } catch (Exception e) {
            throw new LanzouException(LanzouException.FAILED, "上传解析失败: " + e.getMessage());
        } finally {
            if (resp != null) resp.close();
        }
    }

    // ================================================================ 下载

    public Models.DownloadInfo directUrl(String shareUrl, String pwd) throws LanzouException {
        ShareMeta meta = parseSharePage(shareUrl, pwd);
        String api;
        Map<String, String> data = new HashMap<>();
        if ("websign".equals(meta.mode)) {
            api = meta.domain;
            data.put("action", "downprocess");
            data.put("websignkey", meta.ajaxdata);
            data.put("signs", meta.ajaxdata);
            data.put("sign", meta.wpSign);
            data.put("websign", "2");
            data.put("kd", meta.kd);
            data.put("ves", "1");
        } else {
            api = URL_AJAXFILE + "?file=" + meta.fileId;
            data.put("action", "downprocess");
            data.put("sign", meta.sign);
            data.put("kd", meta.kd);
            data.put("p", pwd == null ? "" : pwd);
        }
        JsonObject d = postFormJson(api, data, shareUrl);
        if (!"1".equals(getStr(d, "zt"))) {
            boolean hasPwd = pwd != null && !pwd.isEmpty();
            throw new LanzouException(
                    hasPwd ? LanzouException.PASSWORD_ERROR : LanzouException.FAILED,
                    "取直链失败: " + getStr(d, "inf"));
        }
        String durl = getStr(d, "dom") + "/file/" + getStr(d, "url");
        if ("websign".equals(meta.mode)) durl += "&toolsdown";
        return new Models.DownloadInfo(meta.name, durl);
    }

    public Models.DownloadInfo downloadById(long fid, OutputStream out, ProgressListener listener)
            throws LanzouException {
        Models.ShareInfo s = share(fid, true);
        Models.DownloadInfo info = directUrl(s.url, s.pwd);
        String name = (info.name == null || info.name.isEmpty()) ? ("file_" + fid) : info.name;
        downloadToStream(info.durl, s.url, out, name, listener);
        return info;
    }

    public void downloadToStream(String durl, String referer, final OutputStream out,
                                 final String name, final ProgressListener listener)
            throws LanzouException {
        downloadToStream(durl, referer, "", out, name, listener);
    }

    /**
     * 下载到输出流。
     *
     * 直链会经过「CDN 挑战页 → 302 → 文件节点」多个环节，任一环节都可能返回 HTML，
     * 而这些页面经常被标成 application/octet-stream、或者关键标记出现在 16KB 之后。
     * 因此这里按**正文内容**判断，任何非文件页面都在内存里处理掉，绝不写进输出流。
     *
     * @param pwd 分享提取码（链接过期时用于重新解析，可为空）
     */
    public void downloadToStream(String durl, String referer, String pwd, final OutputStream out,
                                 String name, final ProgressListener listener)
            throws LanzouException {
        String currentUrl = durl;
        boolean htmlTarget = isHtmlFileName(name);
        int verifyRounds = 0;
        int refreshRounds = 0;
        int resetRounds = 0;
        int redirectRounds = 0;
        String lastPage = null;

        for (int attempt = 0; attempt < 10; attempt++) {
            Request req = new Request.Builder().url(currentUrl).get()
                    .header("User-Agent", UA)
                    .header("Referer", referer == null ? "" : referer)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build();
            Response resp = null;
            try {
                resp = downloadHttp.newCall(req).execute();
                if (resp.body() == null) {
                    throw new LanzouException(LanzouException.NETWORK_ERROR, "下载失败：空响应");
                }
                final HttpUrl finalUrl = resp.request().url();
                InputStream stream = resp.body().byteStream();
                byte[] head = new byte[HEAD_SIZE];
                int n = readHead(stream, head);
                String headStr = n > 0 ? new String(head, 0, n, "UTF-8") : "";

                boolean html = !htmlTarget
                        && (AcwSc.looksHtml(head, n) || isHtmlType(resp.header("Content-Type")));

                if (html) {
                    String page = headStr + readAll(stream, PAGE_LIMIT);
                    lastPage = page;

                    // 1) acw_sc__v2 挑战
                    if (AcwSc.isChallenge(page)) {
                        String val = AcwSc.calc(page);
                        if (val.isEmpty()) {
                            throw new LanzouException(LanzouException.NETWORK_ERROR,
                                    "下载失败：无法解析 acw_sc 挑战脚本", page, resp.code());
                        }
                        cookieJar.addHostCookie(finalUrl.host(), "acw_sc__v2", val);
                        // 挑战可能发生在 302 之后的文件节点上，必须重试**最终地址**；
                        // 否则会回到原地址再被 302 到新一轮挑战，永远过不了。
                        currentUrl = finalUrl.toString();
                        continue;
                    }

                    if (!resp.isSuccessful()) {
                        throw htmlPageError(page, resp.code());
                    }

                    // 2) 「验证并下载」中间页
                    if (verifyRounds < 2 && AcwSc.isVerifyPage(page)) {
                        verifyRounds++;
                        String real = resolveVerifyPage(finalUrl, page);
                        if (real != null) {
                            currentUrl = real;
                            continue;
                        }
                    }

                    // 3) 页面自带跳转（JS / meta refresh）
                    if (redirectRounds < 3) {
                        String redirect = AcwSc.findRedirect(page);
                        if (redirect != null && !redirect.isEmpty()) {
                            HttpUrl next = finalUrl.resolve(redirect);
                            if (next != null) {
                                redirectRounds++;
                                currentUrl = next.toString();
                                continue;
                            }
                        }
                    }

                    // 4) 拿到的其实是分享落地页 → 重新解析一次直链（签名可能已过期）
                    if (refreshRounds < 1 && referer != null && !referer.isEmpty()
                            && (page.contains("isngis") || page.contains("ajaxfile.php?file="))) {
                        refreshRounds++;
                        try {
                            Models.DownloadInfo fresh = directUrl(referer, pwd);
                            if (fresh != null && fresh.durl != null && !fresh.durl.isEmpty()) {
                                currentUrl = fresh.durl;
                                continue;
                            }
                        } catch (LanzouException ignored) {
                        }
                    }

                    // 5) 换一套 ESA 会话再试一次（清掉旧 acw_tc / acw_sc__v2）
                    if (resetRounds < 1) {
                        resetRounds++;
                        cookieJar.clearProtection(finalUrl.host());
                        currentUrl = durl;
                        continue;
                    }

                    throw htmlPageError(page, resp.code());
                }

                if (!resp.isSuccessful()) {
                    String page = headStr + readAll(stream, 64 * 1024);
                    throw htmlPageError(page, resp.code());
                }

                long total = resp.body().contentLength();

                // 文件名以服务端 Content-Disposition 为准：分享页标题可能只是「文件」占位，
                // 拿到的名字往往没有扩展名。首个回调把真名带给上层用于重命名。
                String realName = parseContentDisposition(resp.header("Content-Disposition"));
                if (realName != null && !realName.equals(name)) {
                    name = realName;
                    if (listener != null) listener.onProgress(name, total, 0);
                }

                long done = 0;
                if (n > 0) {
                    out.write(head, 0, n);
                    done = n;
                    if (listener != null) listener.onProgress(name, total, done);
                    byte[] buf = new byte[256 * 1024];
                    int c;
                    while ((c = stream.read(buf)) != -1) {
                        out.write(buf, 0, c);
                        done += c;
                        if (listener != null) listener.onProgress(name, total, done);
                    }
                }
                out.flush();
                if (done <= 0) {
                    throw new LanzouException(LanzouException.NETWORK_ERROR,
                            "下载失败：服务器返回了空文件");
                }
                return;
            } catch (IOException e) {
                throw new LanzouException(LanzouException.NETWORK_ERROR, "下载失败: " + e.getMessage());
            } finally {
                if (resp != null) resp.close();
            }
        }
        throw new LanzouException(LanzouException.NETWORK_ERROR,
                lastPage == null ? "下载失败：重试次数过多"
                        : "下载失败：验证未通过（" + describePage(lastPage) + "）");
    }

    private static final int HEAD_SIZE = 16 * 1024;
    private static final int PAGE_LIMIT = 512 * 1024;

    private static boolean isHtmlType(String contentType) {
        if (contentType == null) return false;
        String c = contentType.toLowerCase();
        return c.contains("text/html") || c.contains("application/xhtml");
    }

    private static boolean isHtmlFileName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".html") || n.endsWith(".htm")
                || n.endsWith(".xhtml") || n.endsWith(".mht");
    }

    private static LanzouException htmlPageError(String page, int status) {
        String reason = describePage(page);
        String msg;
        if (status >= 400) {
            msg = "下载失败：HTTP " + status + (reason.isEmpty() ? "" : "（" + reason + "）");
        } else if (!reason.isEmpty()) {
            msg = "下载失败：服务器返回网页（" + reason + "）";
        } else {
            msg = "下载失败：服务器返回网页而非文件";
        }
        return new LanzouException(LanzouException.NETWORK_ERROR, msg, page, status);
    }

    private static String describePage(String html) {
        if (html == null) return "";
        String t = AcwSc.pageTitle(html);
        if (t.isEmpty()) t = AcwSc.snippet(html, 60);
        if (t.length() > 60) t = t.substring(0, 60);
        return t;
    }

    /** 从「验证并下载」中间页 POST ajax.php 换取真实文件地址 */
    private String resolveVerifyPage(HttpUrl pageUrl, String html) {
        String file = AcwSc.grabParam(html, "file");
        String sign = AcwSc.grabParam(html, "sign");
        if (file == null || file.isEmpty()) return null;
        HttpUrl ajax = pageUrl.resolve("/ajax.php");
        if (ajax == null) return null;
        String[] els = {"2", "1", "3"};
        for (int i = 0; i < els.length; i++) {
            FormBody body = new FormBody.Builder()
                    .add("file", file)
                    .add("el", els[i])
                    .add("sign", sign == null ? "" : sign)
                    .build();
            Request req = new Request.Builder().url(ajax).post(body)
                    .header("User-Agent", UA)
                    .header("Referer", pageUrl.toString())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build();
            Response resp = null;
            try {
                resp = http.newCall(req).execute();
                String text = resp.body() == null ? "" : resp.body().string();
                if (text.trim().isEmpty()) continue;
                JsonObject o = new JsonParser().parse(text).getAsJsonObject();
                String url = getStr(o, "url");
                if (!"1".equals(getStr(o, "zt")) || url.isEmpty()) continue;
                String dom = getStr(o, "dom");
                if (!dom.isEmpty()) url = dom + "/file/" + url;
                // ajax 可能返回相对地址，必须基于当前页面补全，否则后续请求会崩
                HttpUrl resolved = pageUrl.resolve(url);
                return resolved != null ? resolved.toString() : url;
            } catch (Exception ignored) {
            } finally {
                if (resp != null) resp.close();
            }
        }
        return null;
    }

    /** 循环读满缓冲区（挑战页可能分多次返回，单次 read 不能保证读全） */
    private static int readHead(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) break;
            total += r;
        }
        return total;
    }

    /** 读取剩余响应体（限制大小，避免异常页面无限膨胀） */
    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        int total = 0;
        while (total < limit && (r = in.read(b)) != -1) {
            buf.write(b, 0, r);
            total += r;
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    // ================================================================ 分享页解析

    private static final class ShareMeta {
        String name;
        String mode;
        String sign;
        String kd = "1";
        String fileId;
        String wpSign;
        String ajaxdata;
        String domain;
    }

    private ShareMeta parseSharePage(String shareUrl, String pwd) throws LanzouException {
        String html = getHtml(shareUrl, null);
        String host = "https://" + Uri.parse(shareUrl).getHost();

        // 只有密码框、没有下载参数 → 需要先提交提取码
        if (!html.contains("var isngis") && html.contains("id=\"pwd\"") && !html.contains("/fn?")) {
            if (pwd == null || pwd.isEmpty()) {
                throw new LanzouException(LanzouException.LACK_PASSWORD, "该文件需要提取码");
            }
            String postUrl = shareUrl;
            Matcher m = Pattern.compile("action=['\"]([^'\"]+)['\"]").matcher(html);
            if (m.find()) {
                postUrl = m.group(1);
                if (postUrl.startsWith("/")) postUrl = host + postUrl;
            }
            Map<String, String> pd = new HashMap<>();
            pd.put("pwd", pwd);
            html = postForm(postUrl, pd, shareUrl);
        }

        ShareMeta meta = new ShareMeta();
        meta.name = withPageExt(pageName(html), html);

        // 链路一：文件夹分享落地页 → iframe /fn? → websign 参数
        Matcher im = Pattern.compile("src=\"(/fn\\?[^\"]+)\"").matcher(html);
        if (im.find()) {
            String ifrHtml = getHtml(host + im.group(1), shareUrl);
            String wp = grab(ifrHtml, "var wp_sign = '([^']+)'");
            String ad = grab(ifrHtml, "var ajaxdata = '([^']+)'");
            String kd = grab(ifrHtml, "var kdns =(\\d+)");
            String dom = grab(ifrHtml, "var domain1 = '([^']+)'");
            if (!wp.isEmpty() && !ad.isEmpty() && !dom.isEmpty()) {
                meta.mode = "websign";
                meta.wpSign = wp;
                meta.ajaxdata = ad;
                meta.kd = kd.isEmpty() ? "1" : kd;
                meta.domain = dom;
                return meta;
            }
        }

        // 链路二：常规分享页
        String sign = grab(html, "var isngis = '([^']+)'");
        if (sign.isEmpty()) {
            // 蓝奏云会给部分页面随机化变量名，此时按值的形态兜底（很长的一段 base64url）
            sign = grab(html, "var\\s+\\w+\\s*=\\s*'([A-Za-z0-9_-]{60,})'");
        }
        String kd = grab(html, "var kdns =(\\d+)");
        String fid = grab(html, "ajaxfile\\.php\\?file=(\\d+)");
        if (sign.isEmpty() || fid.isEmpty()) {
            boolean hasPwd = pwd != null && !pwd.isEmpty();
            throw new LanzouException(
                    hasPwd ? LanzouException.PASSWORD_ERROR : LanzouException.URL_INVALID,
                    "解析分享页失败" + (hasPwd ? "（提取码可能不对）" : ""));
        }
        meta.mode = "sign";
        meta.sign = sign;
        meta.kd = kd.isEmpty() ? "1" : kd;
        meta.fileId = fid;
        return meta;
    }

    private static String pageName(String html) {
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(html);
        if (m.find()) {
            String t = m.group(1).trim().replaceAll("\\s*-\\s*蓝奏云$", "").trim();
            t = unescapeHtml(t);
            if (isUsableFileName(t)) return t;
        }
        // 新版分享页把真名放在 id="filenajax"（未解锁时是「文件」占位）
        m = Pattern.compile("id=\"filenajax\"[^>]*>([^<]*)<").matcher(html);
        if (m.find()) {
            String t = unescapeHtml(m.group(1).trim());
            if (isUsableFileName(t)) return t;
        }
        m = Pattern.compile("font-size:\\s*30px;[^>]*>([^<]+)<").matcher(html);
        if (m.find()) {
            String t = unescapeHtml(m.group(1).trim());
            if (isUsableFileName(t)) return t;
        }
        return null;
    }

    /**
     * 蓝奏云改版后，分享页 {@code <title>} 只有「文件」两个字（占位），
     * 直接拿来当文件名会得到没有扩展名的「文件」。这里过滤掉这类通用占位。
     */
    public static boolean isUsableFileName(String name) {
        if (name == null) return false;
        String t = name.trim();
        if (t.isEmpty() || t.length() > 200) return false;
        if (t.contains("<") || t.contains(">")) return false;
        if (t.indexOf('/') >= 0 || t.indexOf('\\') >= 0) return false;
        String[] junk = {"文件", "文件下载", "下载", "下载文件", "蓝奏云", "蓝奏云盘",
                "undefined", "null", "false", "true", "loading", "加载中"};
        for (String j : junk) {
            if (j.equalsIgnoreCase(t)) return false;
        }
        if (t.contains("蓝奏云")) return false;
        return true;
    }

    static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }

    /**
     * 解析下载响应的 Content-Disposition，取出真实文件名。
     * 形如：{@code attachment;filename="a.apk";filename*=UTF-8''a.apk}
     */
    public static String parseContentDisposition(String cd) {
        if (cd == null || cd.isEmpty()) return null;
        // RFC 5987：filename*=UTF-8''%E4%B8%AD.txt，优先级最高
        Matcher m = Pattern.compile("filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
        if (m.find()) {
            String v = m.group(1).trim();
            int q = v.indexOf("''");
            if (q >= 0) v = v.substring(q + 2);
            v = v.replace("\"", "").trim();
            String d = urlDecode(v);
            if (d != null && isUsableFileName(d)) return d;
        }
        m = Pattern.compile("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", Pattern.CASE_INSENSITIVE).matcher(cd);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v == null) return null;
            String d = urlDecode(v.trim());
            if (d == null) return null;
            d = fixLatin1(d);
            if (isUsableFileName(d)) return d;
        }
        return null;
    }

    private static String urlDecode(String s) {
        if (s == null) return null;
        String v = s.replace('\\', '/');
        int slash = v.lastIndexOf('/');
        if (slash >= 0) v = v.substring(slash + 1);
        v = v.trim();
        if (v.isEmpty()) return null;
        if (v.indexOf('%') >= 0) {
            try {
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return v;
    }

    /** 把「UTF-8 字节被当成 ISO-8859-1」的乱码还原成中文 */
    private static String fixLatin1(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean high = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) return s;      // 已经是正常 Unicode 文本
            if (c >= 0x80) high = true;
        }
        if (!high) return s;
        try {
            String u = new String(s.getBytes("ISO-8859-1"), "UTF-8");
            if (!u.contains("\uFFFD")) return u;
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 名字是否已带合理的扩展名 */
    public static boolean hasExtension(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1);
        if (ext.length() > 8) return false;
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分享页用 {@code <span class="filename"><img src=".../apk_max.gif">} 标出文件类型，
     * 名字缺扩展名时据此补全，避免下载得到没有后缀的文件。
     */
    static String pageFileType(String html) {
        if (html == null) return "";
        String t = grab(html, "<span class=\"filename\"[^>]*>\\s*<img[^>]*/([A-Za-z0-9]{2,8})_(?:max|min)\\.gif");
        if (t.isEmpty()) {
            t = grab(html, "images/type/([A-Za-z0-9]{2,8})_(?:max|min)\\.gif");
        }
        String e = t.toLowerCase();
        if (e.length() < 2 || e.length() > 5) return "";
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c < 'a' || c > 'z') return "";
        }
        if ("jpeg".equals(e)) return "jpg";
        if ("file".equals(e) || "other".equals(e) || "unknown".equals(e) || "none".equals(e)) return "";
        return e;
    }

    /** 缺扩展名时用分享页给出的类型补上 */
    static String withPageExt(String name, String html) {
        if (name == null || name.isEmpty() || hasExtension(name)) return name;
        String ext = pageFileType(html);
        return ext.isEmpty() ? name : name + "." + ext;
    }

    // ================================================================ HTTP 底层

    private JsonObject api(Map<String, String> data) throws LanzouException {
        return api(data, URL_DOUPLOAD, timeout);
    }

    private JsonObject api(Map<String, String> data, String url, long t) throws LanzouException {
        String fullUrl = url;
        if (uid != null && URL_DOUPLOAD.equals(url)) {
            fullUrl = url + "?uid=" + uid;
        }
        FormBody.Builder fb = new FormBody.Builder();
        for (Map.Entry<String, String> e : data.entrySet()) {
            fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        final FormBody form = fb.build();
        final OkHttpClient c = (t == timeout ? http : newClient(t));
        for (int attempt = 0; attempt < 3; attempt++) {
            Request req = new Request.Builder().url(fullUrl).post(form)
                    .header("User-Agent", UA)
                    .header("Referer", HOST + "/mydisk.php")
                    .build();
            Response resp = null;
            try {
                resp = c.newCall(req).execute();
                String body = resp.body() == null ? "" : resp.body().string();
                if (AcwSc.isChallenge(body)) {
                    String host = resp.request().url().host();
                    String val = AcwSc.calc(body);
                    if (val.isEmpty()) {
                        throw new LanzouException(LanzouException.FAILED,
                                "接口被 acw_sc 挑战拦截，且无法解析验证脚本");
                    }
                    cookieJar.addHostCookie(host, "acw_sc__v2", val);
                    continue;
                }
                if (body.trim().isEmpty()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("zt", 1);
                    o.add("text", new JsonArray());
                    return o;
                }
                return new JsonParser().parse(body).getAsJsonObject();
            } catch (IOException e) {
                throw new LanzouException(LanzouException.NETWORK_ERROR, "网络错误: " + e.getMessage());
            } catch (LanzouException e) {
                throw e;
            } catch (Exception e) {
                throw new LanzouException(LanzouException.FAILED, "服务端返回非 JSON: " + e.getMessage());
            } finally {
                if (resp != null) resp.close();
            }
        }
        throw new LanzouException(LanzouException.FAILED, "接口 acw_sc 校验失败：反复返回挑战页");
    }

    private JsonObject postFormJson(String url, Map<String, String> data, String referer)
            throws LanzouException {
        String body = postForm(url, data, referer);
        if (body.trim().isEmpty()) {
            throw new LanzouException(LanzouException.FAILED, "接口返回空响应");
        }
        try {
            return new JsonParser().parse(body).getAsJsonObject();
        } catch (Exception e) {
            throw new LanzouException(LanzouException.FAILED, "接口返回非 JSON: " + e.getMessage());
        }
    }

    private String postForm(String url, Map<String, String> data, String referer)
            throws LanzouException {
        FormBody.Builder fb = new FormBody.Builder();
        for (Map.Entry<String, String> e : data.entrySet()) {
            fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        final FormBody form = fb.build();
        for (int attempt = 0; attempt < 4; attempt++) {
            Request.Builder b = new Request.Builder().url(url).post(form)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9");
            if (referer != null) b.header("Referer", referer);
            Response resp = null;
            try {
                resp = http.newCall(b.build()).execute();
                String body = resp.body() == null ? "" : resp.body().string();
                if (AcwSc.isChallenge(body)) {
                    String host = resp.request().url().host();
                    String val = AcwSc.calc(body);
                    if (val.isEmpty()) return body;
                    cookieJar.addHostCookie(host, "acw_sc__v2", val);
                    continue;
                }
                return body;
            } catch (IOException e) {
                throw new LanzouException(LanzouException.NETWORK_ERROR, "请求失败: " + e.getMessage());
            } finally {
                if (resp != null) resp.close();
            }
        }
        throw new LanzouException(LanzouException.FAILED, "acw_sc 校验失败：页面反复返回挑战");
    }

    private String getHtml(String url, String referer) throws LanzouException {
        return getHtml(url, referer, true);
    }

    private String getHtml(String url, String referer, boolean retry) throws LanzouException {
        for (int attempt = 0; attempt < 4; attempt++) {
            Request.Builder b = new Request.Builder().url(url).get()
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9");
            if (referer != null) b.header("Referer", referer);
            Response resp = null;
            try {
                resp = http.newCall(b.build()).execute();
                String body = resp.body() == null ? "" : resp.body().string();
                if (AcwSc.isChallenge(body)) {
                    String host = resp.request().url().host();
                    String val = AcwSc.calc(body);
                    if (val.isEmpty()) return body;
                    cookieJar.addHostCookie(host, "acw_sc__v2", val);
                    continue;
                }
                return body;
            } catch (IOException e) {
                throw new LanzouException(LanzouException.NETWORK_ERROR, "请求失败: " + e.getMessage());
            } finally {
                if (resp != null) resp.close();
            }
        }
        throw new LanzouException(LanzouException.FAILED, "acw_sc 校验失败：页面反复返回挑战");
    }

    // ================================================================ 工具

    private static String getStr(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }

    private static String grab(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static String safeName(String name) {
        if (name == null) return "";
        String s = name.replace('\u00a0', ' ').replace('\u3000', ' ');
        String forbidden = "$%^!*<>)(+=`'\"\\/:;,?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (forbidden.indexOf(c) < 0) sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 本地保存用的文件名。只处理真正会破坏路径的字符，
     * 保留 {@code ! ' + , ( ) =} 等正常字符 —— 旧版一律删除会把
     * 「photo (1).jpg」变成「photo 1.jpg」、「Rock'n'Roll.mp3」变成「RocknRoll.mp3」。
     */
    public static String localName(String name) {
        if (name == null) return "";
        String s = name.replace('\u00a0', ' ').replace('\u3000', ' ')
                .replace('/', '_').replace('\\', '_')
                .replace(':', '_').replace('*', '_').replace('?', '_')
                .replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        String out = sb.toString().trim();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1).trim();
        if (out.length() > 180) {
            int dot = out.lastIndexOf('.');
            if (dot > 0 && out.length() - dot <= 12) {
                out = out.substring(0, 180 - (out.length() - dot)) + out.substring(dot);
            } else {
                out = out.substring(0, 180);
            }
        }
        return out;
    }

    public static String formatSize(long n) {
        double v = n;
        String[] units = {"B", "KB", "MB", "GB"};
        for (String u : units) {
            if (v < 1024 || "GB".equals(u)) {
                if ("B".equals(u)) return ((long) v) + u;
                return String.format(java.util.Locale.US, "%.1f%s", v, u);
            }
            v /= 1024.0;
        }
        return String.valueOf(n);
    }

    public static Map<String, String> parseCookie(String raw) throws LanzouException {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            throw new LanzouException(LanzouException.ID_ERROR, "cookie 为空");
        }
        raw = raw.trim();
        if (raw.startsWith("{")) {
            try {
                JsonObject o = new JsonParser().parse(raw).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (Exception e) {
                throw new LanzouException(LanzouException.ID_ERROR, "cookie JSON 解析失败");
            }
        } else {
            for (String part : raw.replace('\n', ';').split(";")) {
                String p = part.trim();
                int idx = p.indexOf('=');
                if (idx > 0) {
                    out.put(p.substring(0, idx).trim(), p.substring(idx + 1).trim());
                }
            }
        }
        if (!out.containsKey("ylogin")) {
            throw new LanzouException(LanzouException.ID_ERROR,
                    "cookie 缺少 ylogin 字段，请复制整行 Cookie（含 ylogin 和 phpdisk_info）");
        }
        return out;
    }
}
