package com.lanzou.cloud.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 基于 wolfSSL 的 SSLSocketFactory（用于系统不支持 TLS 1.2 的 API 9~15）。
 *
 * 证书链校验由 wolfSSL C 层完成，因此这里的 X509TrustManager 只作为
 * OkHttp 的占位实现，不参与真正的校验决策。
 */
public class WolfSSLSocketFactory extends SSLSocketFactory {

    private static final X509TrustManager PLACEHOLDER = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final long ctx;

    public WolfSSLSocketFactory() throws IOException {
        long c = WolfTls.nativeCtxNew();
        if (c == 0) throw new IOException("wolfSSL 初始化失败");
        this.ctx = c;
    }

    public X509TrustManager trustManager() {
        return PLACEHOLDER;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return CIPHERS.clone();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return CIPHERS.clone();
    }

    private static final String[] CIPHERS = {
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
    };

    @Override
    public Socket createSocket() throws IOException {
        return new WolfSSLSocket(new Socket(), null, 0, true, ctx);
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return new WolfSSLSocket(s, host, port, autoClose, ctx);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = new Socket(host, port);
        return new WolfSSLSocket(s, host, port, true, ctx);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        Socket s = new Socket(host, port, localHost, localPort);
        return new WolfSSLSocket(s, host, port, true, ctx);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = new Socket(host, port);
        return new WolfSSLSocket(s, host.getHostName(), port, true, ctx);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket s = new Socket(address, port, localAddress, localPort);
        return new WolfSSLSocket(s, address.getHostName(), port, true, ctx);
    }
}
