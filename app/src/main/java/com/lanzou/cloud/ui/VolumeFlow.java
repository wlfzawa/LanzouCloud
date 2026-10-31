package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;
import com.lanzou.cloud.core.Models;
import com.lanzou.cloud.core.VolumeDownloader;
import com.lanzou.cloud.core.VolumeManifest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分卷下载的公共流程：读清单 → 校验分卷是否齐全 → 确认 → 加入下载队列。
 *
 * 两种来源：
 *   · 自己的网盘目录（按文件 id 生成分享链接再解析直链）
 *   · 别人的文件夹分享（按分享码拼地址）
 */
public final class VolumeFlow {

    private static final int MAX_MANIFEST = 512 * 1024;

    private VolumeFlow() {
    }

    // ================================================================ 读清单

    /** 下载清单文件本身并解析；分享链接可能带提取码 */
    public static VolumeManifest read(LanzouClient client, String shareUrl, String pwd)
            throws LanzouException {
        Models.DownloadInfo info = client.directUrl(shareUrl, pwd);
        LimitedOutputStream out = new LimitedOutputStream(MAX_MANIFEST);
        client.downloadToStream(info.durl, shareUrl, pwd, out, "manifest.lanzouvol.txt", null);
        String text = out.asText();
        VolumeManifest m = VolumeManifest.parse(text);
        if (m == null) {
            throw new LanzouException(LanzouException.FAILED,
                    "这不是有效的分卷说明文件，或文件内容已损坏");
        }
        return m;
    }

    // ================================================================ 直链来源

    /** 自己的网盘目录：清单里记了文件 id，优先用 id，找不到再按文件名匹配 */
    public static VolumeDownloader.Source fromFiles(final LanzouClient client,
                                                    final List<Models.FileItem> files) {
        final Map<String, Models.FileItem> byName = new HashMap<String, Models.FileItem>();
        for (Models.FileItem f : files) {
            if (f.name != null) byName.put(f.name, f);
        }
        return new VolumeDownloader.Source() {
            @Override
            public VolumeDownloader.PartUrl resolve(String partName) throws LanzouException {
                Models.FileItem f = byName.get(partName);
                if (f == null) f = findFile(files, partName);
                if (f == null) return null;
                Models.ShareInfo s = client.share(f.id, true);
                if (s == null || s.url == null || s.url.isEmpty()) return null;
                Models.DownloadInfo info = client.directUrl(s.url, s.pwd);
                return new VolumeDownloader.PartUrl(info.durl, s.url, s.pwd);
            }
        };
    }

    /** 别人的文件夹分享：每卷都有自己的分享码 */
    public static VolumeDownloader.Source fromShare(final LanzouClient client,
                                                    final LanzouClient.FolderRef ref,
                                                    final List<Models.ShareFile> files) {
        final Map<String, Models.ShareFile> byName = new HashMap<String, Models.ShareFile>();
        for (Models.ShareFile f : files) {
            if (f.name != null) byName.put(f.name, f);
        }
        return new VolumeDownloader.Source() {
            @Override
            public VolumeDownloader.PartUrl resolve(String partName) throws LanzouException {
                Models.ShareFile f = byName.get(partName);
                if (f == null) f = findShare(files, partName);
                if (f == null) return null;
                String url = ref.baseUrl + "/" + f.code;
                Models.DownloadInfo info = client.directUrl(url, ref.pwd);
                return new VolumeDownloader.PartUrl(info.durl, url, ref.pwd);
            }
        };
    }

    /** 清单里列了、但当前目录里找不到的分卷 */
    public static List<String> missing(VolumeManifest m, List<String> have) {
        List<String> out = new ArrayList<String>();
        if (m == null || m.parts == null) return out;
        for (VolumeManifest.Part p : m.parts) {
            if (p.name == null) continue;
            boolean found = false;
            if (have != null) {
                for (String h : have) {
                    if (samePart(h, p.name)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) out.add(p.name);
        }
        return out;
    }

    /**
     * 文件名是否指向同一卷。
     * 分享页对超长文件名可能截断，精确匹配失败时用 {@code _partNNN} 尾段比对。
     */
    static boolean samePart(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return tail(a).equals(tail(b));
    }

    private static String tail(String name) {
        int i = name.lastIndexOf("_part");
        if (i < 0) return name;
        int dot = name.lastIndexOf('.');
        return dot > i ? name.substring(i, dot) : name.substring(i);
    }

    private static Models.FileItem findFile(List<Models.FileItem> files, String partName) {
        if (files == null) return null;
        for (Models.FileItem f : files) {
            if (samePart(f.name, partName)) return f;
        }
        return null;
    }

    private static Models.ShareFile findShare(List<Models.ShareFile> files, String partName) {
        if (files == null) return null;
        for (Models.ShareFile f : files) {
            if (samePart(f.name, partName)) return f;
        }
        return null;
    }

    public static List<String> namesOfFiles(List<Models.FileItem> files) {
        List<String> out = new ArrayList<String>();
        for (Models.FileItem f : files) {
            if (f.name != null) out.add(f.name);
        }
        return out;
    }

    public static List<String> namesOfShare(List<Models.ShareFile> files) {
        List<String> out = new ArrayList<String>();
        for (Models.ShareFile f : files) {
            if (f.name != null) out.add(f.name);
        }
        return out;
    }

    // ================================================================ 确认入队

    public static void start(final Activity a, final AppPrefs prefs, final VolumeManifest m,
                             final VolumeDownloader.Source source, final String note) {
        if (!DownloadHelper.ensureStorage(a, prefs)) return;

        StringBuilder sb = new StringBuilder();
        sb.append("原文件：").append(m.name).append('\n');
        sb.append("大小：").append(VolumeManifest.human(m.size))
                .append("，分 ").append(m.parts.size()).append(" 卷\n");
        if (m.sha256 != null && !m.sha256.isEmpty()) {
            sb.append("校验：SHA-256 ").append(prefs.isVolumeVerify() ? "开启" : "关闭").append('\n');
        }
        if (note != null && !note.isEmpty()) {
            sb.append('\n').append(note);
        }
        sb.append("\n各卷会依次下载并合并成一个文件，可在「下载列表」查看进度。");

        new AlertDialog.Builder(a)
                .setTitle("分卷合并下载")
                .setMessage(sb.toString())
                .setPositiveButton("开始下载", (d, w) -> enqueue(a, prefs, m, source))
                .setNegativeButton("取消", null)
                .show();
    }

    public static void enqueue(Activity a, AppPrefs prefs, VolumeManifest m,
                               VolumeDownloader.Source source) {
        String name = LanzouClient.localName(new File(m.name).getName());
        if (name.isEmpty()) name = LanzouClient.localName(m.name);
        if (name.isEmpty()) name = "merged.bin";
        DownloadHelper.Dest dest = DownloadHelper.resolveDest(a, prefs, name);
        DownloadCenter.get(a).enqueueVolume(name, m, source, dest);
        toast(a, "已加入下载队列：" + name + "（" + m.parts.size() + " 卷）");
    }

    private static void toast(final Activity a, final String msg) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }

    /** 只收前 N 字节，防止把普通大文件当清单读进内存 */
    static final class LimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final int max;

        LimitedOutputStream(int max) {
            this.max = max;
        }

        @Override
        public void write(int b) {
            if (buf.size() >= max) throw new IllegalStateException("说明文件过大");
            buf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (buf.size() + len > max) throw new IllegalStateException("说明文件过大");
            buf.write(b, off, len);
        }

        String asText() {
            try {
                return buf.toString("UTF-8");
            } catch (Exception e) {
                return new String(buf.toByteArray());
            }
        }
    }
}
