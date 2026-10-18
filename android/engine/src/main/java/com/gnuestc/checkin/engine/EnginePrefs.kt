package com.gnuestc.checkin.engine

import android.content.Context

/**
 * [双包 2026-10-18] 引擎侧本地配置（由主应用经 sync 指令同步过来）
 */
object EnginePrefs {

    private const val NAME = "engine_prefs"
    private const val KEY_KEEPALIVE = "keepalive_enabled"
    private const val KEY_NEXT_TEXT = "next_text"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 常驻服务总开关（跟随主应用的「后台自动打卡服务」开关） */
    fun setKeepAliveEnabled(c: Context, enabled: Boolean) =
        sp(c).edit().putBoolean(KEY_KEEPALIVE, enabled).apply()

    fun isKeepAliveEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_KEEPALIVE, false)

    /** "下次打卡"文案（用于常驻通知第二行） */
    fun setNextText(c: Context, text: String) =
        sp(c).edit().putString(KEY_NEXT_TEXT, text).apply()

    fun getNextText(c: Context): String = sp(c).getString(KEY_NEXT_TEXT, "").orEmpty()
}
