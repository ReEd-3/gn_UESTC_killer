plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// [双包 2026-10-18] 「后台引擎」独立应用：无图标、无界面、不在最近任务显示。
// 由主应用内嵌分发（主包 assets 里携带本 APK）并引导安装；
// 与主应用是两个独立包、相互独立"生死"——划掉主应用不会影响引擎持有的定时任务。
android {
    namespace = "com.gnuestc.checkin.engine"
    compileSdk = 35
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.gnuestc.checkin.engine"
        minSdk = 26
        targetSdk = 35
        // [双包 2026-10-19] v11：电池引导改为厂商无关的"标准意图阶梯"（系统自行路由），去掉硬编码厂商组件
        versionCode = 11
        versionName = "0.11"
    }

    buildTypes {
        release {
            // 与主应用同一 debug 签名：保证签名级权限互通、版本升级一致
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        // 与主应用一致：release 跳过 lintVital，避免联网拉取 lint-gradle 阻塞打包
        checkReleaseBuilds = false
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
    implementation("androidx.core:core-ktx:1.15.0")
    // [v7 巡检链] 引擎侧独立打卡需要 HTTP 客户端（与主应用同款，参数口径一致）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
