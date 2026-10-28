/*
 * wolfssl_jni.c —— 面向 Android 2.3 (API 9)+ 的极简 wolfSSL JNI 绑定。
 *
 * 不复用 wolfssljni 的 Java 层（其 JSSE provider 继承 ExtendedSSLSession /
 * X509ExtendedTrustManager 等 API 24+ 类，在 API 9 上无法加载），
 * 只暴露建立 TLS 连接所必需的一组 C API。
 */

#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#include <wolfssl/ssl.h>
#include <wolfssl/error-ssl.h>
#include <wolfssl/wolfcrypt/error-crypt.h>

#define LOG_TAG "WolfTls"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    (void)vm;
    (void)reserved;
    if (wolfSSL_Init() != WOLFSSL_SUCCESS) {
        LOGE("wolfSSL_Init failed");
    } else {
        LOGI("wolfSSL %s initialized", wolfSSL_lib_version());
    }
    return JNI_VERSION_1_4;   /* API 9 的 Dalvik 只保证 1.4/1.6，用最低版本最稳 */
}

/*
 * 创建 SSL_CTX：TLS 1.2 客户端 + 校验系统 CA。
 * 返回 0 表示失败。
 */
JNIEXPORT jlong JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeCtxNew(JNIEnv* env, jclass cls)
{
    (void)env; (void)cls;

    WOLFSSL_CTX* ctx = wolfSSL_CTX_new(wolfTLSv1_2_client_method());
    if (ctx == NULL) {
        LOGE("wolfSSL_CTX_new failed");
        return 0;
    }

    /* Android 各版本的系统 CA 目录（文件名为 OpenSSL hash 命名，wolfSSL 直接兼容） */
    const char* caDirs[] = {
        "/system/etc/security/cacerts",
        "/apex/com.android.conscrypt/cacerts",
        "/data/misc/keychain/cacerts-added",
        NULL
    };
    int loaded = 0;
    int i;
    for (i = 0; caDirs[i] != NULL; i++) {
        if (access(caDirs[i], R_OK) != 0) continue;
        if (wolfSSL_CTX_load_verify_locations(ctx, NULL, caDirs[i]) == WOLFSSL_SUCCESS) {
            LOGI("CA loaded from %s", caDirs[i]);
            loaded = 1;
            break;
        } else {
            LOGE("CA load failed: %s", caDirs[i]);
        }
    }
    if (!loaded) {
        LOGE("no system CA loaded, handshake will fail");
    }

    wolfSSL_CTX_set_verify(ctx, WOLFSSL_VERIFY_PEER, NULL);
    wolfSSL_CTX_SetMinVersion(ctx, WOLFSSL_TLSV1_2);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeCtxFree(JNIEnv* env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    if (ctxPtr != 0) wolfSSL_CTX_free((WOLFSSL_CTX*)(intptr_t)ctxPtr);
}

JNIEXPORT jlong JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeNewSession(JNIEnv* env, jclass cls, jlong ctxPtr)
{
    (void)env; (void)cls;
    if (ctxPtr == 0) return 0;
    WOLFSSL* ssl = wolfSSL_new((WOLFSSL_CTX*)(intptr_t)ctxPtr);
    return (jlong)(intptr_t)ssl;
}

/*
 * 在已连接的 TCP fd 上完成 TLS 握手。
 * 返回 0 成功；负数表示 wolfSSL 错误码取反。
 */
JNIEXPORT jint JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeConnect(JNIEnv* env, jclass cls,
                                                 jlong sslPtr, jint fd, jstring host)
{
    (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL) return -1;

    wolfSSL_set_fd(ssl, fd);

    if (host != NULL) {
        const char* h = (*env)->GetStringUTFChars(env, host, NULL);
        if (h != NULL) {
            /* SNI：蓝奏云走多域名 CDN，缺少 SNI 会拿到错误证书 */
            wolfSSL_UseSNI(ssl, WOLFSSL_SNI_HOST_NAME, h, (word16)strlen(h));
            wolfSSL_check_domain_name(ssl, h);
            (*env)->ReleaseStringUTFChars(env, host, h);
        }
    }

    int ret = wolfSSL_connect(ssl);
    if (ret != WOLFSSL_SUCCESS) {
        int err = wolfSSL_get_error(ssl, ret);
        char buf[80];
        buf[0] = '\0';
        wolfSSL_ERR_error_string((unsigned long)err, buf);
        LOGE("wolfSSL_connect failed: ret=%d err=%d (%s)", ret, err, buf);
        return -err;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeRead(JNIEnv* env, jclass cls,
                                              jlong sslPtr, jbyteArray data, jint off, jint len)
{
    (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL || data == NULL) return -1;

    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;

    int ret = wolfSSL_read(ssl, buf + off, (int)len);
    if (ret < 0) {
        int err = wolfSSL_get_error(ssl, ret);
        if (err == WOLFSSL_ERROR_ZERO_RETURN) {
            ret = -1;               /* 对端正常关闭，映射为 EOF */
        } else {
            ret = -err;
        }
    }
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeWrite(JNIEnv* env, jclass cls,
                                               jlong sslPtr, jbyteArray data, jint off, jint len)
{
    (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL || data == NULL) return -1;

    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;

    int ret = wolfSSL_write(ssl, buf + off, (int)len);
    if (ret < 0) {
        int err = wolfSSL_get_error(ssl, ret);
        ret = -err;
    }
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeFree(JNIEnv* env, jclass cls, jlong sslPtr)
{
    (void)env; (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL) return;
    wolfSSL_shutdown(ssl);
    wolfSSL_free(ssl);
}

JNIEXPORT jstring JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeGetCipher(JNIEnv* env, jclass cls, jlong sslPtr)
{
    (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL) return NULL;
    const char* c = wolfSSL_get_cipher_name(ssl);
    return c ? (*env)->NewStringUTF(env, c) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_lanzou_cloud_core_WolfTls_nativeGetVersion(JNIEnv* env, jclass cls, jlong sslPtr)
{
    (void)cls;
    WOLFSSL* ssl = (WOLFSSL*)(intptr_t)sslPtr;
    if (ssl == NULL) return NULL;
    const char* v = wolfSSL_get_version(ssl);
    return v ? (*env)->NewStringUTF(env, v) : NULL;
}
