plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xianyu.copilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xianyu.copilot"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置全部从环境变量读取，仓库里不再有任何 keystore 与明文密码。
    // CI 运行时由 Actions Secrets 注入并把 keystore 解码到临时文件。
    // 本地构建若未提供变量，则回退到 debug 签名（仅调试用，不可发布）。
    val ksPath = System.getenv("XIANYU_KS_PATH").orEmpty()
    val ksStorePass = System.getenv("XIANYU_KS_PASS").orEmpty()
    val ksKeyAlias = System.getenv("XIANYU_KS_ALIAS").orEmpty().ifEmpty { "xianyu" }
    val ksKeyPass = System.getenv("XIANYU_KEY_PASS").orEmpty().ifEmpty { ksStorePass }
    val hasReleaseKey = ksPath.isNotBlank() && file(ksPath).exists() && ksStorePass.isNotBlank()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksStorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
