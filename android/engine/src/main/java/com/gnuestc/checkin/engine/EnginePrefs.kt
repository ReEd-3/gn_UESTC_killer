package com.gnuestc.checkin.engine

import android.content.Context

/**
 * [双包 2026-10-18] 引擎侧本地配置（由主应用经 sync 指令同步过来）
 */
object EnginePrefs {

    private const val NAME = "engine_prefs"
    private const val KEY_KEEPALIVE = "keepalive_enabled"
    private const val KEY_NEXT_TEXT = "next_text"
    // [v7 巡检链] 打卡执行所需参数与状态
    private const val KEY_COOKIE = "cookie"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_WIN_START = "win_start"
    private const val KEY_WIN_END = "win_end"
    private const val KEY_LAST_SUCCESS_DATE = "last_success_date"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_LAST_FAIL_SIG = "last_fail_sig"
    // [v10] 电池引导状态
    private const val KEY_BATTERY_PROMPT_DONE = "battery_prompt_done"

    /** 打卡窗口默认值与主应用一致（21:05–23:30） */
    const val DEFAULT_WIN_START = 1265
    const val DEFAULT_WIN_END = 1410

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 常驻服务总开关（跟随主应用的「后台自动打卡服务」开关） */
    fun setKeepAliveEnabled(c: Context, enabled: Boolean) =
        sp(c).edit().putBoolean(KEY_KEEPALIVE, enabled).apply()

    fun isKeepAliveEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_KEEPALIVE, false)

    /** "下次打卡"文案（用于常驻通知第二行） */
    fun setNextText(c: Context, text: String) =
        sp(c).edit().putString(KEY_NEXT_TEXT, text).apply()

    fun getNextText(c: Context): String = sp(c).getString(KEY_NEXT_TEXT, "").orEmpty()

    // ── [v7 巡检链] 会话 / 坐标 / 窗口 / 巡检状态 ─────────────────────
    // 安全备注：cookie 暂以明文存于引擎私有空间（后续可与主应用一致的加密存储）

    fun setCookie(c: Context, v: String) = sp(c).edit().putString(KEY_COOKIE, v).apply()

    fun getCookie(c: Context): String = sp(c).getString(KEY_COOKIE, "").orEmpty()

    fun setLat(c: Context, v: Double) = sp(c).edit().putString(KEY_LAT, v.toString()).apply()

    fun getLat(c: Context): Double = sp(c).getString(KEY_LAT, null)?.toDoubleOrNull() ?: 30.753818

    fun setLng(c: Context, v: Double) = sp(c).edit().putString(KEY_LNG, v.toString()).apply()

    fun getLng(c: Context): Double = sp(c).getString(KEY_LNG, null)?.toDoubleOrNull() ?: 103.934258

    fun setWinStart(c: Context, v: Int) = sp(c).edit().putInt(KEY_WIN_START, v).apply()

    fun getWinStart(c: Context): Int = sp(c).getInt(KEY_WIN_START, DEFAULT_WIN_START)

    fun setWinEnd(c: Context, v: Int) = sp(c).edit().putInt(KEY_WIN_END, v).apply()

    fun getWinEnd(c: Context): Int = sp(c).getInt(KEY_WIN_END, DEFAULT_WIN_END)

    /** 最近一次确认"已打卡"的日期（yyyy-MM-dd） */
    fun setLastSuccessDate(c: Context, v: String) = sp(c).edit().putString(KEY_LAST_SUCCESS_DATE, v).apply()

    fun getLastSuccessDate(c: Context): String = sp(c).getString(KEY_LAST_SUCCESS_DATE, "").orEmpty()

    /** 最近一次"实际尝试打卡"的时间戳（节流用） */
    fun setLastAttemptAt(c: Context, v: Long) = sp(c).edit().putLong(KEY_LAST_ATTEMPT_AT, v).apply()

    fun getLastAttemptAt(c: Context): Long = sp(c).getLong(KEY_LAST_ATTEMPT_AT, 0L)

    /** 最近一次已提醒过的失败原因（相同原因不重复打扰） */
    fun setLastFailSig(c: Context, v: String) = sp(c).edit().putString(KEY_LAST_FAIL_SIG, v).apply()

    fun getLastFailSig(c: Context): String = sp(c).getString(KEY_LAST_FAIL_SIG, "").orEmpty()

    // ── [v10] 电池引导状态 ─────────────────────────────────────

    /** 是否已经引导过"后台运行"设置（引导过一次就不再反复打扰；卸载重装自然重置） */
    fun setBatteryPromptDone(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_BATTERY_PROMPT_DONE, v).apply()

    fun isBatteryPromptDone(c: Context): Boolean = sp(c).getBoolean(KEY_BATTERY_PROMPT_DONE, false)
}
