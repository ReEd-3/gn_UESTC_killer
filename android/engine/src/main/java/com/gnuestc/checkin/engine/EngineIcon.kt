package com.gnuestc.checkin.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * [2026-10-21] 通知图标工具。
 *
 * 背景：引擎发出的通知（常驻 / 打卡结果 / 测试闹钟 / 状态）在通知栏里应显示
 * **主应用（app 本体）的图标**，而不是引擎自己的图标——用户感知的"这个 App"是主应用。
 *
 * 实现：运行时从主应用包读取 launcher 图标（引擎清单已声明 `<queries>` 包可见性）。
 * 好处：不复制资源、不增加体积，主应用换图标时引擎自动跟随；
 * 主应用被卸载/读取失败时返回 null（调用方按"不设置大图标"处理，不影响通知本身）。
 */
object EngineIcon {

    private const val MAIN_PACKAGE = "com.gnuestc.checkin"
    private const val LARGE_ICON_PX = 192

    /**
     * [2026-10-21] 内存缓存：常驻通知的"存活心跳"每秒刷新一次通知，
     * 若不缓存则每秒都要 getApplicationIcon + 缩放解码一次位图（白白消耗 CPU）。
     * 进程重启后自然刷新；主应用换图标时引擎通常也会随之重启，可接受。
     */
    @Volatile
    private var cachedLarge: Bitmap? = null

    /** [review 2026-10-21] 失败时间戳：原实现用永久失败标记，主应用正在更新等瞬时失败后
     *  整个引擎进程生命周期都不会再尝试；改为 5 分钟后允许重试 */
    @Volatile
    private var lastFailAt = 0L

    private const val RETRY_GAP_MS = 5 * 60_000L

    /** 主应用图标位图（用作通知大图标）；失败返回 null */
    fun mainAppLarge(context: Context): Bitmap? {
        cachedLarge?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastFailAt < RETRY_GAP_MS) return null
        val bmp = runCatching {
            context.packageManager
                .getApplicationIcon(MAIN_PACKAGE)
                .toBitmap(LARGE_ICON_PX, LARGE_ICON_PX)
        }.getOrNull()
        if (bmp != null) cachedLarge = bmp else lastFailAt = now
        return bmp
    }
}
