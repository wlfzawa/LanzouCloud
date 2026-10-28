package com.lanzou.cloud.core;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;

/**
 * 用 wolfSSL 承载 TLS 的 SSLSocket 实现，兼容 Android 2.3 (API 9)+。
 *
 * 设计要点：
 * 1. 复用底层已连接的普通 Socket（不重新建连），通过反射拿它的 fd 交给 wolfSSL；
 * 2. 只实现 SSLSocket 在 API 9 就存在的抽象方法，不依赖任何 API 24+ 类型；
 * 3. 证书链校验由 wolfSSL 在 C 层完成（WOLFSSL_VERIFY_PEER + 系统 CA）。
 */
public class WolfSSLSocket extends SSLSocket {

    private final Socket raw;
    private final String host;
    private final int port;
    private final boolean autoClose;

    private long ssl;
    private boolean handshaked;
    private boolean closed;
    private boolean clientMode = true;
    private boolean needClientAuth;
    private boolean wantClientAuth;
    private boolean enableSessionCreation = true;

    private WolfSSLSession session;
    private final List<HandshakeCompletedListener> listeners =
            new ArrayList<HandshakeCompletedListener>(2);

    WolfSSLSocket(Socket raw, String host, int port, boolean autoClose, long ctx) throws IOException {
        if (raw == null) throw new IOException("raw socket is null");
        this.raw = raw;
        this.host = host;
        this.port = port;
        this.autoClose = autoClose;
        this.ssl = WolfTls.nativeNewSession(ctx);
        if (this.ssl == 0) throw new IOException("wolfSSL session create failed");
    }

    /* ============================================================ 握手 */

    @Override
    public void startHandshake() throws IOException {
        if (closed) throw new IOException("socket closed");
        if (handshaked) return;

        int fd = fdOf(raw);
        int ret = WolfTls.nativeConnect(ssl, fd, host);
        if (ret != 0) {
            throw new IOException("wolfSSL handshake failed (code " + (-ret) + "), host=" + host);
        }
        handshaked = true;
        session = new WolfSSLSession(WolfTls.nativeGetCipher(ssl), WolfTls.nativeGetVersion(ssl), host, port);

        synchronized (listeners) {
            for (int i = 0; i < listeners.size(); i++) {
                try {
                    listeners.get(i).handshakeCompleted(null);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void ensureHandshake() throws IOException {
        if (!handshaked) startHandshake();
    }

    /** 反射获取底层 Socket 的 fd（Android 各版本 SocketImpl 都持有 FileDescriptor） */
    private static int fdOf(Socket socket) throws IOException {
        Throwable last = null;

        /* 方式一：Android 内置的 getFileDescriptor$() */
        try {
            Method m = Socket.class.getDeclaredMethod("getFileDescriptor$");
            m.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) m.invoke(socket);
            if (fd != null) return descriptorOf(fd);
        } catch (Throwable t) {
            last = t;
        }

        /* 方式二：反射 impl 字段 + SocketImpl.getFileDescriptor() */
        try {
            Field implField = Socket.class.getDeclaredField("impl");
            implField.setAccessible(true);
            Object impl = implField.get(socket);
            if (impl != null) {
                Method m = impl.getClass().getDeclaredMethod("getFileDescriptor");
                m.setAccessible(true);
                FileDescriptor fd = (FileDescriptor) m.invoke(impl);
                if (fd != null) return descriptorOf(fd);
            }
        } catch (Throwable t) {
            last = t;
        }

        throw new IOException("cannot obtain socket fd: " + last);
    }

    private static int descriptorOf(FileDescriptor fd) throws Exception {
        Field f = FileDescriptor.class.getDeclaredField("descriptor");
        f.setAccessible(true);
        return f.getInt(fd);
    }

    /* ============================================================ 流 */

    @Override
    public InputStream getInputStream() {
        return new WolfInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return new WolfOutputStream();
    }

    private class WolfInputStream extends InputStream {
        private final byte[] one = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            if (n <= 0) return -1;
            return one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            ensureHandshake();
            int n = WolfTls.nativeRead(ssl, b, off, len);
            if (n == -1) return -1;
            if (n < 0) throw new IOException("wolfSSL read error " + (-n));
            return n;
        }

        @Override
        public int available() {
            return 0;
        }
    }

    private class WolfOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            ensureHandshake();
            int total = 0;
            while (total < len) {
                int n = WolfTls.nativeWrite(ssl, b, off + total, len - total);
                if (n <= 0) throw new IOException("wolfSSL write error " + (-n));
                total += n;
            }
        }
    }

    /* ============================================================ Socket 委托 */

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (ssl != 0) {
            try {
                WolfTls.nativeFree(ssl);
            } catch (Throwable ignored) {
            }
            ssl = 0;
        }
        if (autoClose) raw.close();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        raw.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return raw.getSoTimeout();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        raw.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return raw.getTcpNoDelay();
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        raw.setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return raw.getKeepAlive();
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {
        raw.setSoLinger(on, linger);
    }

    @Override
    public int getSoLinger() throws SocketException {
        return raw.getSoLinger();
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {
        raw.setReceiveBufferSize(size);
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return raw.getReceiveBufferSize();
    }

    @Override
    public void setSendBufferSize(int size) throws SocketException {
        raw.setSendBufferSize(size);
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return raw.getSendBufferSize();
    }

    @Override
    public boolean isConnected() {
        return raw.isConnected();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isBound() {
        return raw.isBound();
    }

    @Override
    public InetAddress getInetAddress() {
        return raw.getInetAddress();
    }

    @Override
    public int getPort() {
        return raw.getPort();
    }

    @Override
    public int getLocalPort() {
        return raw.getLocalPort();
    }

    @Override
    public void shutdownInput() throws IOException {
        raw.shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        raw.shutdownOutput();
    }

    /* ============================================================ SSLSocket 抽象方法 */

    private static final String[] CIPHERS = {
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    };

    private static final String[] PROTOCOLS = {"TLSv1.2"};

    @Override
    public String[] getSupportedCipherSuites() {
        return CIPHERS.clone();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return CIPHERS.clone();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        /* wolfSSL 的套件集在 C 层固定，这里接受调用但不下发，避免握手套件不匹配 */
    }

    @Override
    public String[] getSupportedProtocols() {
        return PROTOCOLS.clone();
    }

    @Override
    public String[] getEnabledProtocols() {
        return PROTOCOLS.clone();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        /* 固定 TLSv1.2，忽略外部设置 */
    }

    @Override
    public SSLSession getSession() {
        if (session == null) {
            session = new WolfSSLSession(null, "TLSv1.2", host, port);
        }
        return session;
    }

    @Override
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public void setUseClientMode(boolean mode) {
        this.clientMode = mode;
    }

    @Override
    public boolean getUseClientMode() {
        return clientMode;
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        this.needClientAuth = need;
    }

    @Override
    public boolean getNeedClientAuth() {
        return needClientAuth;
    }

    @Override
    public void setWantClientAuth(boolean want) {
        this.wantClientAuth = want;
    }

    @Override
    public boolean getWantClientAuth() {
        return wantClientAuth;
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        this.enableSessionCreation = flag;
    }

    @Override
    public boolean getEnableSessionCreation() {
        return enableSessionCreation;
    }

    /* ============================================================ 最小 SSLSession */

    static final class WolfSSLSession implements SSLSession {
        private final String cipher;
        private final String protocol;
        private final String peerHost;
        private final int peerPort;
        private final long created = System.currentTimeMillis();
        private volatile boolean valid = true;

        WolfSSLSession(String cipher, String protocol, String peerHost, int peerPort) {
            this.cipher = cipher == null ? "UNKNOWN" : cipher;
            this.protocol = protocol == null ? "TLSv1.2" : protocol;
            this.peerHost = peerHost;
            this.peerPort = peerPort;
        }

        public byte[] getId() {
            return new byte[0];
        }

        public SSLSessionContext getSessionContext() {
            return null;
        }

        public long getCreationTime() {
            return created;
        }

        public long getLastAccessedTime() {
            return created;
        }

        public void invalidate() {
            valid = false;
        }

        public boolean isValid() {
            return valid;
        }

        public void putValue(String name, Object value) {
        }

        public Object getValue(String name) {
            return null;
        }

        public void removeValue(String name) {
        }

        public String[] getValueNames() {
            return new String[0];
        }

        public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            throw new SSLPeerUnverifiedException("not available");
        }

        public Certificate[] getLocalCertificates() {
            return null;
        }

        @SuppressWarnings("deprecation")
        public javax.security.cert.X509Certificate[] getPeerCertificateChain()
                throws SSLPeerUnverifiedException {
            throw new SSLPeerUnverifiedException("not available");
        }

        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
            throw new SSLPeerUnverifiedException("not available");
        }

        public Principal getLocalPrincipal() {
            return null;
        }

        public String getCipherSuite() {
            return cipher;
        }

        public String getProtocol() {
            return protocol;
        }

        public String getPeerHost() {
            return peerHost;
        }

        public int getPeerPort() {
            return peerPort;
        }

        public int getPacketBufferSize() {
            return 17 * 1024;
        }

        public int getApplicationBufferSize() {
            return 16 * 1024;
        }
    }
}
