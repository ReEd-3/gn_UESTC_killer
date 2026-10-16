plugins {
    id("com.android.application") version "8.7.3" apply false
    // 2.2.10：与新版 Android Studio 内置 Kotlin 插件协议匹配
    // （旧版 2.0.21 太老，会导致 IDE 同步报 prepareKotlinBuildScriptModel not found）
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
