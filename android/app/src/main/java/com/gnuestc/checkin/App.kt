package com.gnuestc.checkin

import android.app.Application

/**
 * [修复 2026-10-19] 全局主题入口（治本）：
 *
 * 背景：深/浅色此前是在各 Activity.onCreate 里调用 ThemeManager.apply() 设置。
 * AppCompatDelegate 应用夜间配置存在时序差——Activity 的 Resources 可能仍按旧配置解析颜色，
 * 运行时 ContextCompat.getColor() 就会取到「另一套主题」的颜色。
 * 实测症状：夜间模式下登录按钮文字被设为浅色主题的 #1B1F24，压在夜间卡片 #191E25 上，
 * 肉眼等同"文字消失"（UI 层级里文本仍存在、像素分析检出 226 个 #1B1F24 文字像素）。
 *
 * 改到 Application.onCreate 统一设置后：任何 Activity 创建时 Resources 已是正确配置，
 * 所有运行时取色（登录按钮、最近打卡结果配色等）都稳定。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.setNight(Prefs.isDarkTheme(this))
    }
}
