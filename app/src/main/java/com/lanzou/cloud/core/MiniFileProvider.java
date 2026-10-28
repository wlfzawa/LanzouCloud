package com.lanzou.cloud.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 FileProvider，兼容 Android 2.3 (API 9)+，不依赖 androidx / support 库。
 *
 * URI 形式：content://com.lanzou.cloud.fileprovider/f/&lt;URL 编码的绝对路径&gt;
 * 仅允许访问 App 私有目录与外部存储内的文件，防止越权读取任意路径。
 */
public class MiniFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.lanzou.cloud.fileprovider";

    /** 允许暴露的根目录（绝对路径） */
    private static final List<String> ROOTS = new ArrayList<String>();

    public static synchronized void addRoot(Context ctx, File dir) {
        if (dir == null) return;
        try {
            String p = dir.getCanonicalPath();
            if (!ROOTS.contains(p)) ROOTS.add(p);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void ensureRoots(Context ctx) {
        if (!ROOTS.isEmpty()) return;
        addRoot(ctx, ctx.getFilesDir());
        File downloads = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            addRoot(ctx, downloads);
            addRoot(ctx, downloads.getParentFile());
        }
        addRoot(ctx, ctx.getExternalFilesDir(null));
        addRoot(ctx, Environment.getExternalStorageDirectory());
    }

    private static boolean allowed(Context ctx, File f) {
        ensureRoots(ctx);
        try {
            String p = f.getCanonicalPath();
            synchronized (MiniFileProvider.class) {
                for (int i = 0; i < ROOTS.size(); i++) {
                    String r = ROOTS.get(i);
                    if (p.equals(r) || p.startsWith(r + File.separator)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 为任意文件生成可对外分享的 content:// URI（同时把其父目录加入白名单） */
    public static Uri uriFor(Context ctx, File f) {
        addRoot(ctx, f.getParentFile());
        return Uri.parse("content://" + AUTHORITY + "/f/" + Uri.encode(f.getAbsolutePath()));
    }

    private File resolve(Uri uri) {
        try {
            String p = uri.getPath();
            if (p == null || !p.startsWith("/f/")) return null;
            return new File(Uri.decode(p.substring(3)));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.exists()) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = Long.valueOf(f.length());
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        return f == null ? null : guessMime(f.getName());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        Context ctx = getContext();
        if (ctx == null || !allowed(ctx, f)) throw new FileNotFoundException("forbidden: " + uri);
        int flags = "w".equals(mode)
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, flags);
    }

    public static String guessMime(String name) {
        if (name == null) return "*/*";
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        return "*/*";
    }
}
