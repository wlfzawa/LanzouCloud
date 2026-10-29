package com.lanzou.cloud.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.IOException;

/**
 * 存储访问框架（SAF）小工具：把文件写进用户在设置里选的文件夹。
 *
 * Android 11+ 分区存储后，App 无法再用 File 直写公共目录，
 * 只有用户通过系统文件选择器授权的目录（tree URI）才能自由读写。
 * 这里只实现下载需要的四个动作：建文件、改名、删除、探测可写。
 */
public final class SafUtil {

    private SafUtil() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 21;
    }

    /** 在 tree 目录下创建文件，返回文档 URI */
    public static Uri createFile(Context c, Uri tree, String name) throws IOException {
        if (!isSupported()) throw new IOException("当前系统不支持文件夹授权");
        try {
            ContentResolver cr = c.getContentResolver();
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            String mime = MiniFileProvider.guessMime(name);
            Uri doc = DocumentsContract.createDocument(cr, parent, mime, name);
            if (doc == null) throw new IOException("无法在所选文件夹中创建文件（可能没有写入权限）");
            return doc;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("创建文件失败：" + e.getMessage());
        }
    }

    /** 给文档改名，成功返回新的 URI，失败返回 null */
    public static Uri rename(Context c, Uri doc, String newName) {
        if (!isSupported() || doc == null) return null;
        try {
            return DocumentsContract.renameDocument(c.getContentResolver(), doc, newName);
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除文档（失败静默） */
    public static void delete(Context c, Uri doc) {
        if (!isSupported() || doc == null) return;
        try {
            DocumentsContract.deleteDocument(c.getContentResolver(), doc);
        } catch (Exception ignored) {
        }
    }

    /** 探测目录是否真的可写：建一个临时文件再删掉 */
    public static boolean probe(Context c, Uri tree) {
        if (!isSupported() || tree == null) return false;
        Uri doc = null;
        try {
            doc = createFile(c, tree, "lanzou_write_test.tmp");
            return doc != null;
        } catch (Exception e) {
            return false;
        } finally {
            delete(c, doc);
        }
    }

    /** 读取授权目录的显示名（例如「Download」） */
    public static String displayName(Context c, Uri tree) {
        if (!isSupported() || tree == null) return "";
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(tree,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                return cur.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return "";
    }

    /** 授权是否仍然有效（用户可能在系统设置里撤销过） */
    public static boolean hasPermission(Context c, Uri tree) {
        if (!isSupported() || tree == null) return false;
        if (Build.VERSION.SDK_INT < 19) return true;
        try {
            for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
                if (tree.equals(p.getUri()) && p.isWritePermission()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
