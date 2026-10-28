package com.lanzou.cloud.core;

import java.util.List;

/** 数据模型（对外公开，供 UI 层使用） */
public final class Models {

    private Models() {
    }

    public static final class FileItem {
        public final long id;
        public final String name;
        public final String size;
        public final String time;
        public final String type;
        public final int downs;
        public final boolean hasPwd;
        public final String desc;

        public FileItem(long id, String name, String size, String time, String type,
                        int downs, boolean hasPwd, String desc) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.time = time;
            this.type = type;
            this.downs = downs;
            this.hasPwd = hasPwd;
            this.desc = desc;
        }
    }

    public static final class FolderItem {
        public final long id;
        public final String name;
        public final boolean hasPwd;
        public final String desc;

        public FolderItem(long id, String name, boolean hasPwd, String desc) {
            this.id = id;
            this.name = name;
            this.hasPwd = hasPwd;
            this.desc = desc;
        }
    }

    public static final class DirListing {
        public final List<FolderItem> folders;
        public final List<FileItem> files;

        public DirListing(List<FolderItem> folders, List<FileItem> files) {
            this.folders = folders;
            this.files = files;
        }
    }

    public static final class UploadResult {
        public final long id;
        public final String name;
        public final String size;
        public final String shareUrl;

        public UploadResult(long id, String name, String size, String shareUrl) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.shareUrl = shareUrl;
        }
    }

    public static final class ShareInfo {
        public final String name;
        public final String url;
        public final String pwd;
        public final String desc;

        public ShareInfo(String name, String url, String pwd, String desc) {
            this.name = name;
            this.url = url;
            this.pwd = pwd;
            this.desc = desc;
        }
    }

    public static final class DownloadInfo {
        public final String name;
        public final String durl;

        public DownloadInfo(String name, String durl) {
            this.name = name;
            this.durl = durl;
        }
    }

    /** 文件夹分享里的一个文件 */
    public static final class ShareFile {
        /** 分享码，如 iXXXXXX（用于拼 https://host/iXXXXXX） */
        public final String code;
        public final String name;
        public final String size;
        public final String time;
        /** 类型图标名，同时也是扩展名线索（apk/zip/…） */
        public final String icon;

        public ShareFile(String code, String name, String size, String time, String icon) {
            this.code = code;
            this.name = name;
            this.size = size;
            this.time = time;
            this.icon = icon;
        }
    }

    /** 文件夹分享的一页内容 */
    public static final class FolderShare {
        public final String name;
        public final String baseUrl;
        public final String pwd;
        public final List<ShareFile> files;
        public final int page;
        public final boolean hasMore;

        public FolderShare(String name, String baseUrl, String pwd,
                           List<ShareFile> files, int page, boolean hasMore) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.pwd = pwd;
            this.files = files;
            this.page = page;
            this.hasMore = hasMore;
        }
    }
}
