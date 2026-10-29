package com.lanzou.cloud.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;
import com.lanzou.cloud.core.SafUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内建下载器：后台任务队列，串行下载，进度回写到 Task 供 UI 轮询。
 *
 * 下载记录会持久化到 files/download_history.json，App 重启后仍能在「下载管理」里看到，
 * 也能直接打开已经下完的文件（链接本身会过期，历史记录不提供重试）。
 */
public class DownloadCenter {

    public static final int WAITING = 0;
    public static final int DOWNLOADING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    private static final int MAX_RECORDS = 200;

    public static class Task {
        public final int id;
        /** 展示名，下载中可能被服务端 Content-Disposition 里的真名替换 */
        public volatile String name;
        public final String durl;
        public final String referer;
        public final String pwd;
        /** File 模式下的落盘文件，改名后会指向新文件 */
        public volatile File target;
        /** SAF 模式下的文档 URI */
        public volatile Uri targetUri;
        /** SAF 模式下用户选择的目录树 */
        public volatile Uri tree;
        /** 目录的展示文案（绝对路径或文件夹名） */
        public volatile String dirLabel;
        public volatile int status = WAITING;
        public volatile long total = 0;
        public volatile long now = 0;
        public volatile String error;
        /** 入队时间，历史记录按它排序 */
        public volatile long time = System.currentTimeMillis();
        /** 是否是从磁盘恢复的历史记录（链接已失效，不能重试） */
        public volatile boolean historical;

        Task(int id, String name, String durl, String referer, String pwd) {
            this.id = id;
            this.name = name;
            this.durl = durl;
            this.referer = referer;
            this.pwd = pwd;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) (now * 100 / total);
        }

        /** 用于界面展示的保存位置 */
        public String displayPath() {
            if (target != null) return target.getPath();
            String dir = dirLabel == null || dirLabel.isEmpty() ? "已选文件夹" : dirLabel;
            return dir + "/" + name;
        }
    }

    /** 持久化用的精简记录 */
    private static class Rec {
        String name;
        String path;
        String uri;
        String dir;
        String error;
        long size;
        long time;
        int status;
    }

    private static DownloadCenter instance;

    public static synchronized DownloadCenter get(Context ctx) {
        if (instance == null) {
            instance = new DownloadCenter(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final LanzouClient client;
    private final List<Task> tasks = new ArrayList<Task>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Gson gson = new Gson();
    private final File historyFile;
    /** 历史文件读写的串行锁：下载线程与 UI 线程都可能触发持久化 */
    private final Object ioLock = new Object();

    private DownloadCenter(Context app) {
        this.app = app;
        this.client = Session.get(app).getClient();
        this.historyFile = new File(app.getFilesDir(), "download_history.json");
        restore();
    }

    public Task enqueue(String name, String durl, String referer, String pwd, DownloadHelper.Dest dest) {
        Task t = new Task(seq.getAndIncrement(), name, durl, referer, pwd == null ? "" : pwd);
        t.target = dest.file;
        t.tree = dest.tree;
        t.dirLabel = dest.label;
        synchronized (tasks) {
            tasks.add(0, t);
        }
        persist();
        executor.execute(() -> run(t));
        return t;
    }

    /** 重新下载一个失败的任务（历史记录不支持） */
    public boolean retry(Task t) {
        if (t == null || t.historical) return false;
        if (t.status == DOWNLOADING || t.status == WAITING) return false;
        if (t.durl == null || t.durl.isEmpty()) return false;
        t.status = WAITING;
        t.error = null;
        t.now = 0;
        t.total = 0;
        persist();
        executor.execute(() -> run(t));
        return true;
    }

    public List<Task> list() {
        synchronized (tasks) {
            return new ArrayList<Task>(tasks);
        }
    }

    /** 移除一条任务记录（进行中的任务无法移除） */
    public boolean remove(Task t) {
        if (t.status == DOWNLOADING) return false;
        synchronized (tasks) {
            boolean r = tasks.remove(t);
            if (r) persist();
            return r;
        }
    }

    /** 清空所有已完成/失败的任务记录 */
    public void clearFinished() {
        synchronized (tasks) {
            List<Task> keep = new ArrayList<Task>();
            for (Task t : tasks) {
                if (t.status == DOWNLOADING || t.status == WAITING) keep.add(t);
            }
            tasks.clear();
            tasks.addAll(keep);
        }
        persist();
    }

    private void run(Task t) {
        t.status = DOWNLOADING;
        t.error = null;
        t.now = 0;
        t.total = 0;

        OutputStream os = null;
        Exception err = null;
        try {
            if (t.target != null) {
                File dir = t.target.getParentFile();
                if (dir != null) dir.mkdirs();
                os = new FileOutputStream(t.target);
            } else if (t.tree != null) {
                os = openTreeStream(t);
            } else {
                throw new IllegalStateException("没有下载目标");
            }

            client.downloadToStream(t.durl, t.referer, t.pwd, os, t.name, (name, total, now) -> {
                if (name != null && !name.equals(t.name)) applyRealName(t, name);
                t.total = total;
                t.now = now;
            });
        } catch (Exception e) {
            err = e;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (err == null) {
            if (t.total <= 0) {
                t.total = t.now = fileSize(t);
            } else {
                t.now = t.total;
            }
            t.status = DONE;
            persist();
            return;
        }

        // 失败时删掉半成品，避免用户看到一个残缺文件（尤其是只有几 KB 的验证页）
        deletePartial(t);

        t.status = FAILED;
        t.error = err.getMessage() == null ? err.toString() : err.getMessage();

        // 服务端返回网页时，把原始页面存下来，便于定位是哪一类拦截页
        if (err instanceof LanzouException) {
            LanzouException le = (LanzouException) err;
            if (le.pageHtml != null && !le.pageHtml.isEmpty() && t.target != null) {
                File dbg = new File(t.target.getPath() + ".error.html");
                if (writeText(dbg, le.pageHtml)) {
                    t.error = t.error + "\n诊断页面：" + dbg.getName();
                }
            }
        }
        persist();
    }

    private OutputStream openTreeStream(Task t) throws Exception {
        Uri doc = SafUtil.createFile(app, t.tree, t.name);
        t.targetUri = doc;
        OutputStream os = app.getContentResolver().openOutputStream(doc, "w");
        if (os == null) throw new IllegalStateException("无法写入所选文件夹，请到设置里重新选择");
        return os;
    }

    private long fileSize(Task t) {
        if (t.target != null) return t.target.length();
        return t.now;
    }

    private void deletePartial(Task t) {
        try {
            if (t.target != null && t.target.exists()) {
                t.target.delete();
            } else if (t.targetUri != null && Build.VERSION.SDK_INT >= 21) {
                SafUtil.delete(app, t.targetUri);
                t.targetUri = null;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 服务端下发的真实文件名到达时，把已经开始写的临时文件改成正确名字。
     * 分享页只给出「文件」这类占位名时，下载下来会没有扩展名，
     * 文件管理器也因此显示成通用图标。
     */
    private void applyRealName(Task t, String realName) {
        String safe = LanzouClient.localName(new File(realName).getName());
        if (safe.isEmpty()) {
            t.name = realName;
            return;
        }
        if (t.tree != null) {
            Uri renamed = SafUtil.rename(app, t.targetUri, safe);
            if (renamed != null) t.targetUri = renamed;
            t.name = safe;
            persist();
            return;
        }
        File dir = t.target == null ? null : t.target.getParentFile();
        if (dir == null) {
            t.name = safe;
            return;
        }
        if (t.target.getName().equals(safe)) {
            t.name = safe;
            return;
        }
        File dest = uniqueFile(dir, safe);
        // 同目录 rename 在 Linux 上只改目录项，已打开的写入流继续有效
        if (t.target.renameTo(dest)) {
            t.target = dest;
        }
        t.name = safe;
        persist();
    }

    /** 同名时生成「名字 (1).ext」 */
    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    // ================================================================ 持久化

    private void persist() {
        synchronized (ioLock) {
            List<Rec> recs = new ArrayList<Rec>();
            synchronized (tasks) {
                for (Task t : tasks) {
                    if (t.status != DONE && t.status != FAILED) continue;
                    Rec r = new Rec();
                    r.name = t.name;
                    r.path = t.target == null ? null : t.target.getPath();
                    r.uri = t.targetUri == null ? null : t.targetUri.toString();
                    r.dir = t.dirLabel;
                    r.error = t.error;
                    r.size = t.total;
                    r.time = t.time;
                    r.status = t.status;
                    recs.add(r);
                    if (recs.size() >= MAX_RECORDS) break;
                }
            }
            String json = gson.toJson(recs);
            File tmp = new File(historyFile.getPath() + ".tmp");
            if (writeText(tmp, json)) {
                if (!tmp.renameTo(historyFile)) {
                    writeText(historyFile, json);
                    tmp.delete();
                }
            }
        }
    }

    private void restore() {
        if (!historyFile.exists()) return;
        String json = readText(historyFile);
        if (json == null || json.trim().isEmpty()) return;
        try {
            Type type = new TypeToken<List<Rec>>() {
            }.getType();
            List<Rec> recs = gson.fromJson(json, type);
            if (recs == null) return;
            synchronized (tasks) {
                for (Rec r : recs) {
                    if (r == null || r.name == null) continue;
                    Task t = new Task(seq.getAndIncrement(), r.name, null, null, "");
                    t.status = (r.status == DONE) ? DONE : FAILED;
                    t.error = r.error;
                    t.total = r.size;
                    t.now = r.size;
                    t.time = r.time;
                    t.dirLabel = r.dir;
                    t.historical = true;
                    if (r.path != null) t.target = new File(r.path);
                    if (r.uri != null && Build.VERSION.SDK_INT >= 21) {
                        try {
                            t.targetUri = Uri.parse(r.uri);
                        } catch (Exception ignored) {
                        }
                    }
                    // 上次没下完的，标成失败并说明原因
                    if (r.status != DONE) {
                        t.error = (t.error == null || t.error.isEmpty())
                                ? "上次未下载完成" : t.error;
                    }
                    tasks.add(t);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean writeText(File f, String text) {
        FileOutputStream out = null;
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readText(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
