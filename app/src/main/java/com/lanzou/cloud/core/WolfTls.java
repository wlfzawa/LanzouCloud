package com.lanzou.cloud.core;

/**
 * wolfSSL JNI 入口（自研极简绑定，兼容 Android 2.3 / API 9+）。
 *
 * 没有复用 wolfssljni 的 JSSE provider —— 那套 Java 类继承
 * ExtendedSSLSession / X509ExtendedTrustManager 等 API 24+ 才有的类型，
 * 在 API 9 上类加载就会失败。
 */
public final class WolfTls {

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("wolftls");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private WolfTls() {
    }

    /** native 库是否可用（加载失败时自动降级到系统 TLS） */
    public static boolean available() {
        return LOADED;
    }

    static native long nativeCtxNew();

    static native void nativeCtxFree(long ctx);

    static native long nativeNewSession(long ctx);

    static native int nativeConnect(long ssl, int fd, String host);

    static native int nativeRead(long ssl, byte[] buf, int off, int len);

    static native int nativeWrite(long ssl, byte[] buf, int off, int len);

    static native void nativeFree(long ssl);

    static native String nativeGetCipher(long ssl);

    static native String nativeGetVersion(long ssl);
}
