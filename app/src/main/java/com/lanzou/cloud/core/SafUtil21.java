package com.lanzou.cloud.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;

/**
 * SAF 的 API 21+ 实现。
 *
 * 单独放一个类，是为了让 SafUtil 在 Android 2.3~4.4 上加载时不会因为常量池里
 * 出现 DocumentsContract 这类新 API 而触发 Dalvik 的 VerifyError。
 */
final class SafUtil21 {

    private SafUtil21() {
    }

    static Uri createFile(Context c, Uri tree, String name) throws IOException {
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

    static Uri rename(Context c, Uri doc, String newName) {
        try {
            return DocumentsContract.renameDocument(c.getContentResolver(), doc, newName);
        } catch (Exception e) {
            return null;
        }
    }

    static void delete(Context c, Uri doc) {
        try {
            DocumentsContract.deleteDocument(c.getContentResolver(), doc);
        } catch (Exception ignored) {
        }
    }

    static String displayName(Context c, Uri tree) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(tree,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return "";
    }

    static boolean hasPermission(Context c, Uri tree) {
        try {
            for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
                if (tree.equals(p.getUri()) && p.isWritePermission()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
