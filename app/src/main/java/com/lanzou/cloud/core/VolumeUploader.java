package com.lanzou.cloud.core;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * 分卷上传：把一个大文件按固定大小切片，逐卷上传到同一个文件夹，
 * 最后上传一份 {@link VolumeManifest} 说明文件。
 *
 * 整个过程中只顺序读一遍源文件：边上传边计算整文件 SHA-256，
 * 不会为了校验再读第二遍，也不会在本地生成临时分卷。
 */
public final class VolumeUploader {

    public interface Listener {
        /**
         * @param index     当前第几卷（从 1 开始）
         * @param count     总卷数
         * @param partName  当前卷的文件名
         * @param totalDone 整个文件已上传的字节数
         * @param totalSize 整个文件的字节数
         */
        void onProgress(int index, int count, String partName, long totalDone, long totalSize);

        boolean isCancelled();
    }

    public static final class Result {
        public final VolumeManifest manifest;
        public final Models.UploadResult note;
        public final int partCount;

        Result(VolumeManifest manifest, Models.UploadResult note) {
            this.manifest = manifest;
            this.note = note;
            this.partCount = manifest.parts.size();
        }
    }

    private VolumeUploader() {
    }

    /**
     * @param src      源文件流，方法内部不会关闭
     * @param fileName 原始文件名（决定分卷名与清单名）
     * @param size     原始文件字节数，必须准确
     * @param folderId 目标文件夹，-1 表示根目录
     * @param partMb   单卷大小（MB）
     */
    public static Result upload(LanzouClient client, InputStream src, String fileName,
                                long size, long folderId, int partMb, Listener listener)
            throws LanzouException {
        if (size <= 0) {
            throw new LanzouException(LanzouException.FAILED, "无法确定文件大小，不能分卷上传");
        }
        long partBytes = Math.max(1, partMb) * 1024L * 1024L;
        int count = (int) ((size + partBytes - 1) / partBytes);
        if (count < 2) {
            throw new LanzouException(LanzouException.FAILED, "文件未超过单卷大小，无需分卷");
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new LanzouException(LanzouException.FAILED, "当前系统不支持 SHA-256");
        }

        VolumeManifest m = new VolumeManifest();
        m.name = fileName;
        m.size = size;
        m.partSize = partBytes;
        m.created = System.currentTimeMillis();

        DigestInputStream din = new DigestInputStream(src, md);
        long done = 0;
        try {
            for (int i = 1; i <= count; i++) {
                if (listener != null && listener.isCancelled()) {
                    throw new LanzouException(LanzouException.FAILED, "已取消上传");
                }
                final int idx = i;
                final long base = done;
                final long thisSize = Math.min(partBytes, size - done);
                final String pn = VolumeManifest.partName(fileName, i, count);

                BoundedInputStream bin = new BoundedInputStream(din, thisSize);
                Models.UploadResult r = client.upload(bin, pn, thisSize, folderId,
                        (name, total, now) -> {
                            if (listener != null) {
                                listener.onProgress(idx, count, pn, base + now, size);
                            }
                        });

                VolumeManifest.Part p = new VolumeManifest.Part();
                p.index = i;
                p.name = r.name == null || r.name.trim().isEmpty() ? pn : r.name;
                p.size = thisSize;
                p.fid = String.valueOf(r.id);
                p.share = r.shareUrl;
                m.parts.add(p);

                done += thisSize;
                if (listener != null) listener.onProgress(i, count, p.name, done, size);
            }
        } finally {
            try {
                din.close();
            } catch (Exception ignored) {
            }
        }

        m.sha256 = hex(md.digest());

        // 清单最后上传：它出现时，前面的分卷一定已经全部就绪
        byte[] text;
        try {
            text = m.toText().getBytes("UTF-8");
        } catch (Exception e) {
            throw new LanzouException(LanzouException.FAILED, "生成说明文件失败");
        }
        String noteName = VolumeManifest.manifestName(fileName);
        Models.UploadResult note = client.upload(new ByteArrayInputStream(text), noteName,
                text.length, folderId, null);
        return new Result(m, note);
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

    /** 只允许读 limit 字节，读到上限后返回 -1；不关闭底层流 */
    static final class BoundedInputStream extends FilterInputStream {
        private long left;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = in.skip(Math.min(n, left));
            if (s > 0) left -= s;
            return s;
        }

        @Override
        public int available() throws IOException {
            long a = in.available();
            return (int) Math.min(a, left);
        }

        @Override
        public void close() {
            // 底层流由调用方管理
        }
    }
}
