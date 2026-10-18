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

    /** 与引擎 BootstrapActivity 约定：启动意图携带的"是否开启常驻"开关 */
    private const val EXTRA_KEEPALIVE = "keepalive"

    private const val ASSET_NAME = "engine.apk"
    private const val PREFS = "engine_bridge"
    private const val KEY_EXTRACT_MARKER = "extract_marker"

    data class EngineInfo(
        val installed: Boolean,
        val installedVersionCode: Int,
        val bundledVersionCode: Int,
        val contentDiffers: Boolean = false
    ) {
        val needsInstall: Boolean
            get() = !installed || installedVersionCode < bundledVersionCode || contentDiffers
    }

    fun query(context: Context): EngineInfo {
        @Suppress("DEPRECATION")
        val installedCode = runCatching {
            context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0).versionCode
        }.getOrDefault(-1)
        val bundledCode = bundledVersionCode(context) ?: 0
        val installed = installedCode > 0
        // [修复 2026-10-18] 版本号相同时对比内容哈希：引擎内容变了但版本号没及时升时也能识别更新
        var contentDiffers = false
        if (installed && installedCode == bundledCode) {
            val installedHash = runCatching {
                context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0).applicationInfo?.sourceDir
                    ?.let { sha256(File(it)) }
            }.getOrNull()
            val bundledHash = runCatching {
                extractBundledApk(context)?.let { sha256(it) }
            }.getOrNull()
            contentDiffers = installedHash != null && bundledHash != null && installedHash != bundledHash
        }
        return EngineInfo(installed, installedCode, bundledCode, contentDiffers)
    }

    private fun sha256(f: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()

    /** 是否允许安装（Android 8+ 需要"安装未知应用"权限） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 提取内嵌引擎 APK（缓存于应用缓存目录；内嵌资源变化时自动重新提取） */
    fun extractBundledApk(context: Context): File? {
        return runCatching {
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val out = File(dir, ASSET_NAME)
            // [修复 2026-10-18] 原实现仅按"主应用版本号"判断缓存是否过期：主应用版本号不变时，
            // 内嵌资源已更新也不会重新提取（导致一直是旧引擎）→ 改为对比资源大小，
            // 并保留版本号标记兜底
            val assetSize = runCatching {
                context.assets.open(ASSET_NAME).use { it.available().toLong() }
            }.getOrDefault(-1L)
            val marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_EXTRACT_MARKER, -1)
            if (!out.exists() || marker != BuildConfig.VERSION_CODE || out.length() != assetSize) {
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
            // [修复 2026-10-18] 同时显式指定接收组件：确保指令必定送达引擎（双保险）
            setClassName(ENGINE_PACKAGE, "$ENGINE_PACKAGE.EngineReceiver")
            putExtra("cmd", cmd)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            configure?.invoke(this)
        }
        context.sendBroadcast(intent)
    }

    /**
     * 拉起引擎的引导页（解除 stopped 状态 + 申请权限 + 按开关状态启停常驻服务）；
     * 未安装时静默失败。[修复 2026-10-18] 携带"是否应常驻"开关：避免引导页
     * 因尚未收到 sync 指令而错过启动常驻服务的时机。
     */
    fun activateEngine(context: Context) {
        val wantKeepAlive = Prefs.isServiceEnabled(context) && Prefs.getCookie(context).isNotBlank()
        runCatching {
            context.startActivity(
                Intent().setClassName(ENGINE_PACKAGE, ENGINE_BOOTSTRAP_ACTIVITY)
                    .putExtra(EXTRA_KEEPALIVE, wantKeepAlive)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
