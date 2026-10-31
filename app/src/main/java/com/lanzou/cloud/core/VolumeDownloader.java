package com.lanzou.cloud.core;

import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * 分卷合并下载：按清单顺序逐个取直链、下载、顺序写入同一个输出流。
 *
 * 进度按整文件字节数上报，界面看到的是「1.2 GB 里的 700 MB」，
 * 而不是当前这一卷的百分比。
 */
public final class VolumeDownloader {

    /** 单卷的下载入口，由调用方决定怎么把卷名换成直链（网盘 id / 分享链接） */
    public static final class PartUrl {
        public final String durl;
        public final String referer;
        public final String pwd;

        public PartUrl(String durl, String referer, String pwd) {
            this.durl = durl;
            this.referer = referer == null ? "" : referer;
            this.pwd = pwd == null ? "" : pwd;
        }
    }

    public interface Source {
        PartUrl resolve(String partName) throws LanzouException;
    }

    private VolumeDownloader() {
    }

    public static void download(LanzouClient client, VolumeManifest m, Source source,
                                OutputStream out, boolean verify, ProgressListener listener)
            throws LanzouException {
        List<VolumeManifest.Part> parts = m.parts;
        if (parts == null || parts.isEmpty()) {
            throw new LanzouException(LanzouException.FAILED, "分卷清单为空");
        }

        MessageDigest md = null;
        if (verify && m.sha256 != null && !m.sha256.isEmpty()) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception ignored) {
            }
        }
        OutputStream sink = md == null ? out : new DigestOutputStream(out, md);

        long total = m.size;
        long done = 0;
        for (int i = 0; i < parts.size(); i++) {
            VolumeManifest.Part p = parts.get(i);
            if (p == null || p.name == null || p.name.trim().isEmpty()) {
                throw new LanzouException(LanzouException.FAILED,
                        "清单里第 " + (i + 1) + " 卷缺少文件名");
            }
            PartUrl u = source.resolve(p.name);
            if (u == null || u.durl == null || u.durl.isEmpty()) {
                throw new LanzouException(LanzouException.FAILED,
                        "第 " + (i + 1) + " 卷下载地址解析失败：" + p.name);
            }
            final long base = done;
            client.downloadToStream(u.durl, u.referer, u.pwd, sink, p.name,
                    (name, partTotal, now) -> {
                        if (listener != null) listener.onProgress(m.name, total, base + now);
                    });
            done += p.size > 0 ? p.size : 0;
            if (listener != null) listener.onProgress(m.name, total, done);
        }

        try {
            sink.flush();
        } catch (Exception ignored) {
        }

        if (md != null) {
            String actual = hex(md.digest());
            if (!actual.equalsIgnoreCase(m.sha256)) {
                throw new LanzouException(LanzouException.FAILED,
                        "合并完成，但校验不一致\n期望 " + shortSha(m.sha256)
                                + "\n实际 " + shortSha(actual)
                                + "\n文件可能不完整，请重新下载");
            }
        }
        if (listener != null) listener.onProgress(m.name, total, total);
    }

    private static String shortSha(String s) {
        if (s == null) return "";
        return s.length() > 16 ? s.substring(0, 16) + "…" : s;
    }

    private static String hex(byte[] d) {
        final char[] h = "0123456789abcdef".toCharArray();
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int b = d[i] & 0xff;
            out[i * 2] = h[b >>> 4];
            out[i * 2 + 1] = h[b & 0x0f];
        }
        return new String(out);
    }
}
