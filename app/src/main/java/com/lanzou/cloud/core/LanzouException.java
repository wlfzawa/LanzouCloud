package com.lanzou.cloud.core;

/** 蓝奏云异常，携带错误码；服务端返回网页时附带原始页面用于诊断 */
public class LanzouException extends Exception {

    public static final int SUCCESS = 0;
    public static final int FAILED = -1;
    public static final int ID_ERROR = 1;
    public static final int PASSWORD_ERROR = 2;
    public static final int LACK_PASSWORD = 3;
    public static final int MKDIR_ERROR = 5;
    public static final int URL_INVALID = 6;
    public static final int PATH_ERROR = 8;
    public static final int NETWORK_ERROR = 9;
    public static final int OFFICIAL_LIMITED = 11;

    public final int code;

    /** 非文件响应（HTML 页面）原文，供界面落盘成诊断文件；正常错误为 null */
    public String pageHtml;
    /** 对应的 HTTP 状态码，未知为 0 */
    public int httpStatus;

    public LanzouException(int code, String message) {
        super(message);
        this.code = code;
    }

    public LanzouException(int code, String message, String pageHtml, int httpStatus) {
        super(message);
        this.code = code;
        this.pageHtml = pageHtml;
        this.httpStatus = httpStatus;
    }
}
