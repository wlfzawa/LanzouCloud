plugins {
    id("com.android.application")
}

android {
    namespace = "com.lanzou.cloud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lanzou.cloud"
        // 最低支持 Android 2.3 (API 9)：系统 TLS 栈太老，TLS 1.2 由 wolfSSL 承载
        minSdk = 9
        targetSdk = 34
        // 每次构建自动用当前 unix 时间戳作为版本号，保证每版唯一且递增
        val buildTs = System.currentTimeMillis() / 1000L
        versionCode = buildTs.toInt()
        versionName = "1.4.0-beta.$buildTs"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        jniLibs {
            // minSdk < 23：必须让系统在安装时解压 .so
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 3.11.x 不含 java.time.Duration 引用（3.12 的 callTimeout 会引入 API 26 类型）
    implementation("com.squareup.okhttp3:okhttp:3.11.0")
    implementation("com.google.code.gson:gson:2.8.9")
}
