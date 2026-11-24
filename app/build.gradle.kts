plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepfish.xiaohei"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepfish.xiaohei"
        minSdk = 26
        targetSdk = 34
        // OTA 用 versionName（tag v1.0）判版本；versionCode 每次发版必须递增，
        // 否则系统安装器会以「降级」为由拒装。规则：1.0 -> 100，1.1 -> 110，1.0.1 -> 101。
        versionCode = 100
        versionName = "1.0"
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // 素材是已解密的原始 PNG/OGG/JSON，无需压缩，避免 AAPT 再处理一遍
    androidResources {
        noCompress += listOf("png", "ogg", "wav", "json")
    }
}

// 零外部依赖：WebView 是系统组件，其余全用 Framework，离线即可构建。
dependencies {
}
