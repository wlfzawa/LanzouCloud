package com.lanzou.cloud.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 为 Android 4.1~4.4（API 16~20）强制启用系统 TLS 1.2。
 * 这些系统的 OpenSSL 其实已支持 TLS 1.2，只是默认未启用。
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        this.delegate = ctx.getSocketFactory();
    }

    private Socket enable(Socket s) {
        if (s instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) s;
            try {
                String[] supported = ssl.getSupportedProtocols();
                boolean has12 = false;
                if (supported != null) {
                    for (int i = 0; i < supported.length; i++) {
                        if ("TLSv1.2".equals(supported[i])) {
                            has12 = true;
                            break;
                        }
                    }
                }
                if (has12) ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return enable(delegate.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enable(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enable(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return enable(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enable(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return enable(delegate.createSocket(address, port, localAddress, localPort));
    }
}
