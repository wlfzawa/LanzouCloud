package com.lanzou.cloud.ui;

import android.content.Context;

import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 内建下载器：后台任务队列，串行下载，进度回写到 Task 供 UI 轮询 */
public class DownloadCenter {

    public static final int WAITING = 0;
    public static final int DOWNLOADING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    public static class Task {
        public final int id;
        /** 展示名，下载中可能被服务端 Content-Disposition 里的真名替换 */
        public volatile String name;
        public final String durl;
        public final String referer;
        public final String pwd;
        /** 落盘路径，改名后会指向新文件 */
        public volatile File target;
        public volatile int status = WAITING;
        public volatile long total = 0;
        public volatile long now = 0;
        public volatile String error;

        Task(int id, String name, String durl, String referer, String pwd, File target) {
            this.id = id;
            this.name = name;
            this.durl = durl;
            this.referer = referer;
            this.pwd = pwd;
            this.target = target;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) (now * 100 / total);
        }
    }

    private static DownloadCenter instance;

    public static synchronized DownloadCenter get(Context ctx) {
        if (instance == null) {
            instance = new DownloadCenter(ctx.getApplicationContext());
        }
        return instance;
    }

    private final LanzouClient client;
    private final List<Task> tasks = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger seq = new AtomicInteger(1);

    private DownloadCenter(Context app) {
        this.client = Session.get(app).getClient();
    }

    public Task enqueue(String name, String durl, String referer, File target) {
        return enqueue(name, durl, referer, "", target);
    }

    public Task enqueue(String name, String durl, String referer, String pwd, File target) {
        Task t = new Task(seq.getAndIncrement(), name, durl, referer,
                pwd == null ? "" : pwd, target);
        synchronized (tasks) {
            tasks.add(0, t);
        }
        executor.execute(() -> run(t));
        return t;
    }

    /** 重新下载一个失败的任务 */
    public boolean retry(Task t) {
        if (t == null || t.status == DOWNLOADING || t.status == WAITING) return false;
        t.status = WAITING;
        t.error = null;
        t.now = 0;
        t.total = 0;
        executor.execute(() -> run(t));
        return true;
    }

    public List<Task> list() {
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    /** 移除一条任务记录（进行中的任务无法移除） */
    public boolean remove(Task t) {
        if (t.status == DOWNLOADING) return false;
        synchronized (tasks) {
            return tasks.remove(t);
        }
    }

    /** 清空所有已完成/失败的任务记录 */
    public void clearFinished() {
        synchronized (tasks) {
            List<Task> keep = new ArrayList<>();
            for (Task t : tasks) {
                if (t.status == DOWNLOADING || t.status == WAITING) keep.add(t);
            }
            tasks.clear();
            tasks.addAll(keep);
        }
    }

    private void run(Task t) {
        t.status = DOWNLOADING;
        t.error = null;
        t.now = 0;
        t.total = 0;

        FileOutputStream fos = null;
        Exception err = null;
        try {
            File dir = t.target.getParentFile();
            if (dir != null) dir.mkdirs();
            fos = new FileOutputStream(t.target);
            client.downloadToStream(t.durl, t.referer, t.pwd, fos, t.name, (name, total, now) -> {
                if (name != null && !name.equals(t.name)) applyRealName(t, name);
                t.total = total;
                t.now = now;
            });
        } catch (Exception e) {
            err = e;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (err == null) {
            t.status = DONE;
            return;
        }

        // 失败时删掉半成品，避免用户看到一个残缺文件（尤其是只有几 KB 的验证页）
        if (t.target.exists() && !t.target.delete()) {
            // 删不掉也无所谓，错误信息里会带上路径
        }
        t.status = FAILED;
        t.error = err.getMessage() == null ? err.toString() : err.getMessage();

        // 服务端返回网页时，把原始页面存下来，便于定位是哪一类拦截页
        if (err instanceof LanzouException) {
            LanzouException le = (LanzouException) err;
            if (le.pageHtml != null && !le.pageHtml.isEmpty()) {
                File dbg = new File(t.target.getPath() + ".error.html");
                if (writeText(dbg, le.pageHtml)) {
                    t.error = t.error + "\n诊断页面：" + dbg.getName();
                }
            }
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
        File dir = t.target.getParentFile();
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
}
