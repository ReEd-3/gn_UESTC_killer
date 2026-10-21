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

    /** 主应用图标位图（用作通知大图标）；失败返回 null */
    fun mainAppLarge(context: Context): Bitmap? = runCatching {
        context.packageManager
            .getApplicationIcon(MAIN_PACKAGE)
            .toBitmap(LARGE_ICON_PX, LARGE_ICON_PX)
    }.getOrNull()
}
