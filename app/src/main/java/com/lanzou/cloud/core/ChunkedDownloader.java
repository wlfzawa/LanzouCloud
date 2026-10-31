package com.lanzou.cloud.core;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 分片下载：多线程 Range 请求 + 断点续传。
 *
 * 落盘结构：
 *   xxx.apk.part       已下载的内容（预分配为完整长度）
 *   xxx.apk.part.json  每片的起止与已完成字节，断点续传时校验总大小与 ETag
 *
 * 任一分片失败时不删除 .part，下次重试从各片断点继续。
 */
final class ChunkedDownloader {

    /** 小于 4 MB 的文件分片收益不明显，直接单流下载 */
    private static final long MIN_TOTAL = 4L * 1024 * 1024;
    /** 每片至少 2 MB，避免请求数过多 */
    private static final long MIN_CHUNK = 2L * 1024 * 1024;
    private static final int MAX_THREADS = 6;
    /** 进度落盘间隔 */
    private static final long SAVE_MS = 3000;

    private static final Gson GSON = new Gson();

    static class Meta {
        long total;
        String etag;
        long[] start;
        long[] end;
        long[] done;
    }

    private ChunkedDownloader() {
    }

    static void download(LanzouClient client, String durl, String referer, String pwd,
                         File target, int threads, LanzouClient.RemoteFile known,
                         ProgressListener listener)
            throws LanzouException {
        LanzouClient.RemoteFile f = known != null ? known : client.probe(durl, referer, pwd);
        if (f.total <= 0 || !f.ranged || f.total < MIN_TOTAL || threads <= 1) {
            downloadSingle(client, durl, referer, pwd, target, listener);
            return;
        }

        final String name = f.name == null || f.name.isEmpty() ? target.getName() : f.name;
        final File part = new File(target.getPath() + ".part");
        final File metaFile = new File(target.getPath() + ".part.json");

        Meta meta = loadMeta(metaFile);
        if (!valid(meta, f)) {
            meta = new Meta();
            meta.total = f.total;
            meta.etag = f.etag;
            plan(meta, f.total, threads);
            part.delete();
        }
        final Meta m = meta;
        final long already = sum(m.done);
        final AtomicLong done = new AtomicLong(already);
        if (listener != null) listener.onProgress(name, f.total, already);

        try {
            RandomAccessFile raf = new RandomAccessFile(part, "rw");
            try {
                if (raf.length() != f.total) raf.setLength(f.total);
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            throw new LanzouException(LanzouException.NETWORK_ERROR,
                    "无法创建临时文件: " + e.getMessage());
        }

        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> url = new AtomicReference<>(f.url);
        final ExecutorService pool = Executors.newFixedThreadPool(m.start.length);
        final CountDownLatch latch = new CountDownLatch(m.start.length);

        for (int i = 0; i < m.start.length; i++) {
            final int idx = i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetch(client, durl, referer, pwd, part, metaFile, m, idx, url,
                                done, name, listener, abort);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        abort.set(true);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        pool.shutdown();

        try {
            latch.await(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            abort.set(true);
            Thread.currentThread().interrupt();
        }

        synchronized (m) {
            saveMeta(metaFile, m);
        }

        Throwable err = failure.get();
        if (err != null) {
            throw new LanzouException(LanzouException.NETWORK_ERROR,
                    err.getMessage() == null || err.getMessage().isEmpty()
                            ? "下载中断，已保留进度" : "下载中断，已保留进度：" + err.getMessage());
        }
        if (part.length() != f.total) {
            throw new LanzouException(LanzouException.NETWORK_ERROR,
                    "下载不完整（" + part.length() + "/" + f.total + "），已保留进度");
        }
        if (target.exists() && !target.delete()) {
            throw new LanzouException(LanzouException.FAILED, "无法覆盖已有文件：" + target.getPath());
        }
        if (!part.renameTo(target)) {
            copyTo(part, target);
            part.delete();
        }
        metaFile.delete();
        if (listener != null) listener.onProgress(name, f.total, f.total);
    }

    /** 单流下载（服务端不支持 Range，或文件较小） */
    private static void downloadSingle(LanzouClient client, String durl, String referer,
                                       String pwd, File target, ProgressListener listener)
            throws LanzouException {
        new File(target.getPath() + ".part").delete();
        new File(target.getPath() + ".part.json").delete();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            client.downloadToStream(durl, referer, pwd, out, target.getName(), listener);
        } catch (IOException e) {
            throw new LanzouException(LanzouException.NETWORK_ERROR, "下载失败: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void fetch(LanzouClient client, String durl, String referer, String pwd,
                              File part, File metaFile, Meta m, int idx,
                              AtomicReference<String> url, AtomicLong done, String name,
                              ProgressListener listener, AtomicBoolean abort) throws Exception {
        long pos = m.start[idx] + m.done[idx];
        long end = m.end[idx];
        if (pos > end) return;

        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        long lastSave = 0;
        int rounds = 0;
        try {
            while (pos <= end) {
                if (abort.get()) throw new IOException("已取消");

                Request req = new Request.Builder().url(url.get()).get()
                        .header("User-Agent", LanzouClient.UA)
                        .header("Referer", referer == null ? "" : referer)
                        .header("Range", "bytes=" + pos + "-" + end)
                        .build();
                Response resp = null;
                try {
                    resp = client.downloadClient().newCall(req).execute();
                    String cr = resp.header("Content-Range");
                    if (resp.code() == 206 && resp.body() != null && parseStart(cr) == pos) {
                        InputStream in = resp.body().byteStream();
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            int w = (int) Math.min(n, end - pos + 1);
                            if (w <= 0) break;
                            raf.seek(pos);
                            raf.write(buf, 0, w);
                            pos += w;
                            m.done[idx] += w;
                            long d = done.addAndGet(w);
                            if (listener != null) listener.onProgress(name, m.total, d);
                            long now = System.currentTimeMillis();
                            if (now - lastSave > SAVE_MS) {
                                lastSave = now;
                                synchronized (m) {
                                    saveMeta(metaFile, m);
                                }
                            }
                            if (pos > end) break;
                        }
                        continue;
                    }

                    // 直链有时效，节点也可能返回验证页：重新探测一次再继续
                    if (rounds++ >= 2) {
                        throw new IOException("HTTP " + resp.code());
                    }
                    synchronized (client) {
                        LanzouClient.RemoteFile fresh = client.probe(durl, referer, pwd);
                        if (fresh.total != m.total) {
                            throw new IOException("文件大小已变化，请重新下载");
                        }
                        url.set(fresh.url);
                    }
                } finally {
                    if (resp != null) resp.close();
                }
            }
        } finally {
            raf.close();
        }
    }

    private static void plan(Meta m, long total, int threads) {
        int n = Math.max(1, Math.min(threads, MAX_THREADS));
        long chunk = (total + n - 1) / n;
        if (chunk < MIN_CHUNK) {
            n = (int) Math.max(1, total / MIN_CHUNK);
            chunk = (total + n - 1) / n;
        }
        m.start = new long[n];
        m.end = new long[n];
        m.done = new long[n];
        for (int i = 0; i < n; i++) {
            m.start[i] = i * chunk;
            m.end[i] = Math.min(total - 1, (i + 1) * chunk - 1);
        }
    }

    private static boolean valid(Meta m, LanzouClient.RemoteFile f) {
        if (m == null || m.total != f.total) return false;
        if (m.etag != null && f.etag != null && !m.etag.equals(f.etag)) return false;
        if (m.start == null || m.end == null || m.done == null) return false;
        if (m.start.length == 0 || m.start.length != m.end.length) return false;
        if (m.start.length != m.done.length) return false;
        for (int i = 0; i < m.start.length; i++) {
            if (m.start[i] < 0 || m.end[i] < m.start[i] - 1 || m.done[i] < 0) return false;
        }
        return true;
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            if (v > 0) s += v;
        }
        return s;
    }

    private static long parseStart(String cr) {
        if (cr == null) return -1;
        int sp = cr.indexOf(' ');
        if (sp < 0) return -1;
        int dash = cr.indexOf('-', sp);
        if (dash < 0) return -1;
        try {
            return Long.parseLong(cr.substring(sp + 1, dash).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static Meta loadMeta(File f) {
        if (!f.exists()) return null;
        try {
            return GSON.fromJson(readAll(f), Meta.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveMeta(File f, Meta m) {
        try {
            writeAll(f, GSON.toJson(m));
        } catch (Exception ignored) {
        }
    }

    private static String readAll(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            int len = (int) Math.min(f.length(), 1 << 20);
            byte[] buf = new byte[Math.max(len, 1)];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1) {
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void copyTo(File src, File dst) throws LanzouException {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            throw new LanzouException(LanzouException.FAILED, "保存文件失败: " + e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }
}
