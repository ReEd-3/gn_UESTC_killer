pluginManagement {
    repositories {
        // 国内镜像优先；如后续使用代理，可删除下面三行
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先；如后续使用代理，可删除下面两行
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "GNCheckin"
include(":app")
// [双包 2026-10-18] 后台引擎独立应用（无界面；由主应用内嵌分发并引导安装）
include(":engine")
