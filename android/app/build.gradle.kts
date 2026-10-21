plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gnuestc.checkin"
    compileSdk = 35
    // 使用本机已安装的 Build Tools（避免构建时联网补齐）
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.gnuestc.checkin"
        minSdk = 26
        targetSdk = 35
        // [发布 2026-10-22] 0.4.5（vc10）：安装流程改前台拉起系统安装器（修红米不弹安装界面）；
        // 首次引导后补弹引擎安装；权限页电池豁免改查后台组件
        versionCode = 10
        versionName = "0.4.5"
    }

    buildTypes {
        release {
            // [签名 2026-10-17] 使用 debug 签名打包：与现有安装同签名、可无缝覆盖升级；
            // 正式对外分发前建议换独立 keystore。请备份 %USERPROFILE%\.android\debug.keystore，
            // 丢失后无法对已安装用户发布「可覆盖升级」的新版本。
            signingConfig = signingConfigs.getByName("debug")
            // [L0 加固 2026-10-17] 原配置：isMinifyEnabled = false（无混淆）
            // 开启 R8 代码混淆/压缩 + 资源压缩；保留规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // [L0 加固配套 2026-10-17] release 跳过 lintVital：
        // 该任务需联网拉取 lint-gradle，镜像不稳定时会阻塞打包；如需恢复删除下面一行即可
        checkReleaseBuilds = false
    }

    // [双包 2026-10-18] 内嵌引擎 APK：构建时由 :engine 拷贝进该目录，随主包一并分发
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("embeddedEngine"))
        }
    }

    // [review 2026-10-21] 内嵌引擎 APK 不压缩：
    // 好处：运行时可先用 AssetFileDescriptor.length 取长度（不必整包读进内存）就能判断是否需要重新提取；
    // 安装时也能直接流式拷贝。APK 本身就是已压缩包，再压一次收益极小。
    androidResources {
        noCompress += "apk"
    }

    buildFeatures {
        viewBinding = true
        // 生成 BuildConfig，界面底部展示版本号用
        buildConfig = true
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// [双包 2026-10-18] 主包构建前，先将引擎 APK 拷贝进内嵌资源目录（随主包一起分发）
val syncEngineApk = tasks.register<Copy>("syncEngineApk") {
    dependsOn(":engine:assembleRelease")
    from(project(":engine").layout.buildDirectory.file("outputs/apk/release/engine-release.apk"))
    into(layout.buildDirectory.dir("embeddedEngine"))
    rename { "engine.apk" }
}
tasks.named("preBuild") { dependsOn(syncEngineApk) }
