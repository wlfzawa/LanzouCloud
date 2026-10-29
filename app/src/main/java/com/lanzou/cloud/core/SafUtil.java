package com.lanzou.cloud.core;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;

/**
 * 存储访问框架（SAF）小工具：把文件写进用户在设置里选的文件夹。
 *
 * Android 11+ 分区存储后，App 无法再用 File 直写公共目录，
 * 只有用户通过系统文件选择器授权的目录（tree URI）才能自由读写。
 * 这里只实现下载需要的四个动作：建文件、改名、删除、探测可写。
 *
 * 真正的平台调用在 SafUtil21 里，避免老系统加载本类时碰到 API 21 才有的类型。
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
        return SafUtil21.createFile(c, tree, name);
    }

    /** 给文档改名，成功返回新的 URI，失败返回 null */
    public static Uri rename(Context c, Uri doc, String newName) {
        if (!isSupported() || doc == null) return null;
        return SafUtil21.rename(c, doc, newName);
    }

    /** 删除文档（失败静默） */
    public static void delete(Context c, Uri doc) {
        if (!isSupported() || doc == null) return;
        SafUtil21.delete(c, doc);
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
        return SafUtil21.displayName(c, tree);
    }

    /** 授权是否仍然有效（用户可能在系统设置里撤销过） */
    public static boolean hasPermission(Context c, Uri tree) {
        if (!isSupported() || tree == null) return false;
        return SafUtil21.hasPermission(c, tree);
    }
}
