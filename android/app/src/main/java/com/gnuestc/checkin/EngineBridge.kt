package com.gnuestc.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * [双包 2026-10-18] 与「后台引擎」组件（独立应用 com.gnuestc.checkin.engine）的桥接：
 *  - 检测引擎是否已安装 / 是否需更新（对比内嵌版本号）
 *  - 从内嵌资源提取引擎 APK 并引导系统安装
 *  - 通过（签名级权限保护的）显式广播向引擎发送控制指令
 *
 * 引擎无图标、无界面、不在最近任务显示；与主应用是两个独立包，
 * 划掉/清理主应用不会影响引擎持有的定时任务。
 */
object EngineBridge {

    const val ENGINE_PACKAGE = "com.gnuestc.checkin.engine"
    const val ENGINE_BOOTSTRAP_ACTIVITY = "com.gnuestc.checkin.engine.BootstrapActivity"
    private const val ACTION_CMD = "com.gnuestc.checkin.engine.action.CMD"
    private const val ASSET_NAME = "engine.apk"
    private const val PREFS = "engine_bridge"
    private const val KEY_EXTRACT_MARKER = "extract_marker"

    data class EngineInfo(
        val installed: Boolean,
        val installedVersionCode: Int,
        val bundledVersionCode: Int
    ) {
        val needsInstall: Boolean get() = !installed || installedVersionCode < bundledVersionCode
    }

    fun query(context: Context): EngineInfo {
        @Suppress("DEPRECATION")
        val installedCode = runCatching {
            context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0).versionCode
        }.getOrDefault(-1)
        val bundledCode = bundledVersionCode(context) ?: 0
        return EngineInfo(installedCode > 0, installedCode, bundledCode)
    }

    /** 是否允许安装（Android 8+ 需要"安装未知应用"权限） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 提取内嵌引擎 APK（缓存于应用缓存目录；主应用升级后自动重新提取） */
    fun extractBundledApk(context: Context): File? {
        return runCatching {
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val out = File(dir, ASSET_NAME)
            val marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_EXTRACT_MARKER, -1)
            if (!out.exists() || marker != BuildConfig.VERSION_CODE) {
                context.assets.open(ASSET_NAME).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_EXTRACT_MARKER, BuildConfig.VERSION_CODE).apply()
            }
            out
        }.getOrNull()
    }

    private fun bundledVersionCode(context: Context): Int? {
        val f = extractBundledApk(context) ?: return null
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.versionCode
    }

    /** 拉起系统安装器安装/更新引擎；返回是否成功发起 */
    fun installOrUpdate(context: Context): Boolean {
        val apk = extractBundledApk(context) ?: return false
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
    }

    /** 发送控制指令给引擎（显式广播；允许投递给"已安装但未启动过"的应用） */
    fun sendCommand(context: Context, cmd: String, configure: ((Intent) -> Unit)? = null) {
        val intent = Intent(ACTION_CMD).apply {
            setPackage(ENGINE_PACKAGE)
            putExtra("cmd", cmd)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            configure?.invoke(this)
        }
        context.sendBroadcast(intent)
    }

    /** 尝试拉起引擎的引导页（解除 stopped 状态 + 申请通知权限）；未安装时静默失败 */
    fun activateEngine(context: Context) {
        runCatching {
            context.startActivity(
                Intent().setClassName(ENGINE_PACKAGE, ENGINE_BOOTSTRAP_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
