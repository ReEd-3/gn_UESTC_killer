package com.gnuestc.checkin

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 本地配置存取。
 * 隐私相关数据（SESSION Cookie 等）经 SecretStore 使用 Android Keystore
 * （AES-256-GCM）加密后写入；旧版本遗留的明文数据会在读取时自动迁移为密文。
 */
object Prefs {
    private const val NAME = "gn_checkin"
    private const val KEY_COOKIE = "session_cookie"
    private const val KEY_LAST = "last_result"
    private const val KEY_NEXT = "next_trigger"
    private const val KEY_SERVICE = "service_enabled"
    private const val KEY_DARK = "dark_theme"
    private const val KEY_LOC_LAT = "loc_lat"
    private const val KEY_LOC_LNG = "loc_lng"
    private const val KEY_DEF_LAT = "def_lat"
    private const val KEY_DEF_LNG = "def_lng"
    private const val KEY_DEF_NAME = "def_name"
    private const val KEY_DEF_RADIUS = "def_radius"
    private const val KEY_WIN_START = "win_start_minute"
    private const val KEY_WIN_END = "win_end_minute"
    private const val KEY_WIN_SYNCED = "win_synced"

    // [加密改造 2026-10-17] 原明文实现保留备查：
    // fun getCookie(c: Context): String = sp(c).getString(KEY_COOKIE, "") ?: ""
    // fun setCookie(c: Context, v: String) = sp(c).edit().putString(KEY_COOKIE, v).apply()
    fun getCookie(c: Context): String {
        val raw = sp(c).getString(KEY_COOKIE, "") ?: ""
        if (raw.isBlank()) return ""
        if (SecretStore.isEncrypted(raw)) {
            val decrypted = SecretStore.decrypt(raw)
            if (decrypted != null) return decrypted
            // 密文无法解密（密钥失效或数据损坏）：清除坏数据，用户重新登录即可
            sp(c).edit().remove(KEY_COOKIE).apply()
            return ""
        }
        // 旧版本遗留的明文会话：本次读取后自动迁移为密文
        setCookie(c, raw)
        return raw
    }

    fun setCookie(c: Context, v: String) {
        // 加密不可用时降级为原文写入，保证极端情况下登录态仍可用
        val stored = if (v.isBlank()) v else (SecretStore.encrypt(v) ?: v)
        sp(c).edit().putString(KEY_COOKIE, stored).apply()
    }

    fun setLastResult(c: Context, v: String) = sp(c).edit().putString(KEY_LAST, v).apply()
    fun getLastResult(c: Context): String? = sp(c).getString(KEY_LAST, null)

    fun setNextTrigger(c: Context, millis: Long) = sp(c).edit().putLong(KEY_NEXT, millis).apply()
    fun getNextTrigger(c: Context): Long = sp(c).getLong(KEY_NEXT, 0L)

    /** 后台打卡服务总开关（默认开启） */
    fun setServiceEnabled(c: Context, enabled: Boolean) = sp(c).edit().putBoolean(KEY_SERVICE, enabled).apply()
    fun isServiceEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_SERVICE, true)

    /** 深色主题：首次启动跟随系统，之后以用户选择为准 */
    fun isDarkTheme(c: Context): Boolean {
        val sp = sp(c)
        if (sp.contains(KEY_DARK)) return sp.getBoolean(KEY_DARK, true)
        return (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun setDarkTheme(c: Context, dark: Boolean) = sp(c).edit().putBoolean(KEY_DARK, dark).apply()

    /** 脚本定位点（GCJ02）；未设置过时用「本账号默认打卡点」 */
    fun setLocation(c: Context, lat: Double, lng: Double) {
        sp(c).edit()
            .putString(KEY_LOC_LAT, lat.toString())
            .putString(KEY_LOC_LNG, lng.toString())
            .apply()
    }

    fun getLocationLat(c: Context): Double =
        sp(c).getString(KEY_LOC_LAT, null)?.toDoubleOrNull() ?: getDefaultLat(c)

    fun getLocationLng(c: Context): Double =
        sp(c).getString(KEY_LOC_LNG, null)?.toDoubleOrNull() ?: getDefaultLng(c)

    fun isCustomLocation(c: Context): Boolean = sp(c).contains(KEY_LOC_LAT)

    fun resetLocation(c: Context) {
        sp(c).edit().remove(KEY_LOC_LAT).remove(KEY_LOC_LNG).apply()
    }

    /** 本账号默认打卡点（登录后从服务端提取；未提取到时用内置兜底值） */
    fun setDefaultPoint(c: Context, lat: Double, lng: Double, name: String, radiusMeters: Double) {
        sp(c).edit()
            .putString(KEY_DEF_LAT, lat.toString())
            .putString(KEY_DEF_LNG, lng.toString())
            .putString(KEY_DEF_NAME, name)
            .putString(KEY_DEF_RADIUS, radiusMeters.toString())
            .apply()
    }

    fun getDefaultLat(c: Context): Double =
        sp(c).getString(KEY_DEF_LAT, null)?.toDoubleOrNull() ?: CheckinApi.LATITUDE

    fun getDefaultLng(c: Context): Double =
        sp(c).getString(KEY_DEF_LNG, null)?.toDoubleOrNull() ?: CheckinApi.LONGITUDE

    fun getDefaultName(c: Context): String =
        sp(c).getString(KEY_DEF_NAME, null)?.ifBlank { "打卡点" } ?: "打卡点"

    fun getDefaultRadius(c: Context): Double =
        sp(c).getString(KEY_DEF_RADIUS, null)?.toDoubleOrNull() ?: CheckinApi.FENCE_RADIUS_M

    fun isDefaultPointKnown(c: Context): Boolean = sp(c).contains(KEY_DEF_LAT)

    /** 打卡时间窗口（当天 00:00 起的分钟数）：默认 21:05–23:30，登录/打卡时自动同步服务端覆盖 */
    fun setWindow(c: Context, startMinute: Int, endMinute: Int, synced: Boolean) {
        sp(c).edit()
            .putInt(KEY_WIN_START, startMinute)
            .putInt(KEY_WIN_END, endMinute)
            .putBoolean(KEY_WIN_SYNCED, synced)
            .apply()
    }

    fun getWindowStartMinute(c: Context): Int =
        sp(c).getInt(KEY_WIN_START, DEFAULT_WINDOW_START_MINUTE)

    fun getWindowEndMinute(c: Context): Int =
        sp(c).getInt(KEY_WIN_END, DEFAULT_WINDOW_END_MINUTE)

    fun isWindowSynced(c: Context): Boolean = sp(c).getBoolean(KEY_WIN_SYNCED, false)

    fun resetWindow(c: Context) {
        sp(c).edit().remove(KEY_WIN_START).remove(KEY_WIN_END).remove(KEY_WIN_SYNCED).apply()
    }

    /** 形如 "21:05–23:30" 的窗口展示文案 */
    fun windowLabel(c: Context): String = String.format(
        Locale.US, "%02d:%02d–%02d:%02d",
        getWindowStartMinute(c) / 60, getWindowStartMinute(c) % 60,
        getWindowEndMinute(c) / 60, getWindowEndMinute(c) % 60
    )

    /** 默认窗口：21:05 触发 / 23:30 截止 */
    const val DEFAULT_WINDOW_START_MINUTE = 21 * 60 + 5
    const val DEFAULT_WINDOW_END_MINUTE = 23 * 60 + 30

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
