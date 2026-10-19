package com.gnuestc.checkin

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate

/** 深色/浅色主题管理：首次启动跟随系统，之后以用户选择为准 */
object ThemeManager {

    /**
     * [修复 2026-10-19] 已改由 App(Application.onCreate) 统一应用（避免 Resources 配置竞态）；
     * Activity 不再调用，保留备查。
     */
    fun apply(activity: Activity) {
        setNight(Prefs.isDarkTheme(activity))
    }

    /** 切换主题（AppCompat 会自动重建界面，实现无缝切换） */
    fun setNight(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
