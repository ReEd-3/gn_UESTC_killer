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
        // [双包 2026-10-19] v15：引导闭环——去掉 noHistory，跳系统页后返回即复检；未生效当场升级到应用信息页/厂商耗电页
        // [文案 2026-10-19] v16：正式版文案定稿（引擎通知/权限弹窗去开发者用语；日志与测试类文案保持）
        // [修复 2026-10-19] v17：电池引导收敛——弹过即记账（每档一次）；同次激活不再连环引导；返回后延迟复查防误判
        // [修复 2026-10-19] v18：补 INTERNET 权限（此前巡检全部报 missing INTERNET permission）；会话失效时停止巡检
        // [修复 2026-10-19] v19：打卡结果通知可点击打开主应用（会话失效提示需可达）
        // [修复 2026-10-19] v20：sync 携带"今日已完成"（app 成功后引擎立即撤今日网格）；Cookie 改为 Keystore 加密（旧明文自动迁移）
        // [修复 2026-10-19] v21：巡检重排防抖（签名未变 2 分钟内不重排）；sync 时服务已运行则不再重复 start/复活闹钟
        // [修复 2026-10-20] v22：UA 补微信小程序标识（服务端新增来源校验）；业务硬拒绝停止巡检
        // [修复 2026-10-21] v25：通知大图标改为主应用（app 本体）图标（运行时读取主包，非引擎图标）
        // [保活 2026-10-22] v27：新增会话心跳链（方案 A）——全天候每 30 分钟只读 GET /today，
        // 避免会话在打卡窗口外的长时间静置中被服务端判定失效
        versionCode = 27
        versionName = "0.27"
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
