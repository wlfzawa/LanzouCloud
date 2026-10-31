package com.lanzou.cloud.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 分卷清单。
 *
 * 大文件超过单文件上限时切成若干卷上传，再上传一个 {@code 原名.lanzouvol.txt}。
 * 这个文件前半段是给人看的说明，后半段是给客户端解析的 JSON，
 * 因此用文本编辑器能读懂，客户端也能直接据此下载合并。
 */
public final class VolumeManifest {

    public static final String FORMAT = "lanzou-vol/1";
    /** 清单固定后缀，同时满足蓝奏云的上传白名单（txt） */
    public static final String SUFFIX = ".lanzouvol.txt";
    private static final String JSON_MARK = "----- 以下为程序数据";

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String format = FORMAT;
    /** 原始文件名 */
    public String name;
    /** 原始文件字节数 */
    public long size;
    /** 原始文件 SHA-256（小写十六进制） */
    public String sha256;
    /** 每卷字节数 */
    public long partSize;
    public long created;
    public List<Part> parts = new ArrayList<Part>();

    public static final class Part {
        public int index;
        /** 云端实际文件名（服务端可能对重名做调整） */
        public String name;
        public long size;
        /** 网盘文件 id，同账号可直接用 */
        public String fid;
        /** 单文件分享链接，跨账号时可用 */
        public String share;
        public String sha256;
    }

    public static boolean isManifest(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(SUFFIX);
    }

    /** 由原始文件名生成清单名：xxx.iso → xxx.lanzouvol.txt */
    public static String manifestName(String fileName) {
        String base = stripExt(fileName);
        return clip(base, 60) + SUFFIX;
    }

    /**
     * 分卷名：原文件名的基名 + _part001.zip。
     *
     * 后缀强制用 zip，因为蓝奏只接受白名单后缀，原后缀（mp4/mkv…）会被拒绝。
     * 分隔符用下划线而不是点：实测 {@code xxx.part001.zip} 会被服务端拒绝（错误 7071），
     * {@code xxx_part001.zip} 正常；卷内是裸二进制切片，不是真的 zip，说明文件里已写明。
     */
    public static String partName(String fileName, int index, int count) {
        int digits = count >= 1000 ? 4 : 3;
        String num = String.valueOf(index);
        while (num.length() < digits) num = "0" + num;
        return clip(stripExt(fileName), 50) + "_part" + num + ".zip";
    }

    private static String stripExt(String fileName) {
        String base = fileName == null || fileName.trim().isEmpty() ? "file" : fileName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base;
    }

    private static String clip(String s, int max) {
        if (s == null) return "file";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    // ================================================================ 文本

    /** 生成说明文件：人类可读 + 机器可解析 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("蓝奏云分卷文件说明\n");
        sb.append("============================================================\n");
        sb.append("文件名称：").append(name).append('\n');
        sb.append("文件大小：").append(human(size)).append("（").append(size).append(" 字节）\n");
        sb.append("分卷数量：").append(parts.size()).append(" 个，每卷约 ")
                .append(human(partSize)).append('\n');
        if (sha256 != null && !sha256.isEmpty()) {
            sb.append("SHA-256：").append(sha256).append('\n');
        }
        sb.append("创建时间：").append(time(created)).append("\n\n");

        sb.append("怎么还原\n");
        sb.append("1. 用本客户端打开本文件所在的文件夹，点击本说明文件，\n");
        sb.append("   客户端会按编号顺序下载全部分卷并自动合并。\n");
        sb.append("2. 手动处理：下载下列全部分卷，按编号从小到大首尾相接\n");
        sb.append("   （二进制拼接，不要解压），得到的文件即为原文件。\n\n");

        sb.append("分卷清单\n");
        for (Part p : parts) {
            sb.append("  ").append(pad(p.index)).append("  ").append(p.name)
                    .append("  ").append(human(p.size)).append('\n');
        }
        sb.append('\n');
        sb.append(JSON_MARK).append("，请勿修改 -----\n");
        sb.append(GSON.toJson(this)).append('\n');
        return sb.toString();
    }

    private static String pad(int i) {
        String s = String.valueOf(i);
        while (s.length() < 3) s = "0" + s;
        return s;
    }

    /** 从说明文件里取出 JSON；找不到或格式不对返回 null */
    public static VolumeManifest parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String body = text;
        int mark = text.indexOf(JSON_MARK);
        if (mark >= 0) body = text.substring(mark);
        int s = body.indexOf('{');
        int e = body.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            VolumeManifest m = GSON.fromJson(body.substring(s, e + 1), VolumeManifest.class);
            if (m == null || m.parts == null || m.parts.isEmpty()) return null;
            if (m.name == null || m.name.trim().isEmpty()) return null;
            if (m.size <= 0) {
                long sum = 0;
                for (Part p : m.parts) sum += p.size;
                m.size = sum;
            }
            return m;
        } catch (Exception e1) {
            return null;
        }
    }

    public String totalText() {
        return human(size) + " · " + parts.size() + " 卷";
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    private static String time(long t) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t));
        } catch (Exception e) {
            return String.valueOf(t);
        }
    }
}
