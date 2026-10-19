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
    // [v11] 电池引导阶梯位置
    private const val KEY_BATTERY_PROMPT_STAGE = "battery_prompt_stage"
    // [v14] 测试闹钟轮换 requestCode
    private const val KEY_TEST_ALARM_RC = "test_alarm_rc"
    // [修复 2026-10-19] 巡检重排防抖：状态签名 + 上次重排时间
    private const val KEY_LAST_SCHED_SIG = "last_schedule_sig"
    private const val KEY_LAST_SCHED_AT = "last_schedule_at"

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
    // [修复 2026-10-19] v20 起 Cookie 改为 Keystore 加密存储（与主应用一致）；旧明文读取时自动迁移

    fun setCookie(c: Context, v: String) {
        val stored = if (v.isBlank()) v else (EngineSecretStore.encrypt(v) ?: v)
        sp(c).edit().putString(KEY_COOKIE, stored).apply()
    }

    fun getCookie(c: Context): String {
        val raw = sp(c).getString(KEY_COOKIE, "").orEmpty()
        if (raw.isBlank()) return ""
        if (EngineSecretStore.isEncrypted(raw)) {
            return EngineSecretStore.decrypt(raw) ?: run {
                // 密钥失效/数据损坏：清除坏数据，等主应用下次 sync 重新下发
                sp(c).edit().remove(KEY_COOKIE).apply()
                ""
            }
        }
        // 旧版本遗留的明文会话：本次读取后迁移为密文
        setCookie(c, raw)
        return raw
    }

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

    // ── [v10/v11] 电池引导状态 ─────────────────────────────────

    /** 是否已经引导过"后台运行"设置（引导过一次就不再反复打扰；卸载重装自然重置） */
    fun setBatteryPromptDone(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_BATTERY_PROMPT_DONE, v).apply()

    fun isBatteryPromptDone(c: Context): Boolean = sp(c).getBoolean(KEY_BATTERY_PROMPT_DONE, false)

    /** [v11] 电池引导阶梯位置（0=请求豁免 1=优化列表 2=应用详情） */
    fun setBatteryPromptStage(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_BATTERY_PROMPT_STAGE, v).apply()

    fun getBatteryPromptStage(c: Context): Int = sp(c).getInt(KEY_BATTERY_PROMPT_STAGE, 0)

    // ── [v14] 测试闹钟轮换 requestCode ─────────────────────────

    /** 上一次测试闹钟的 requestCode（每次换新，避免继承旧闹钟被系统推迟的状态） */
    fun getTestAlarmRc(c: Context): Int = sp(c).getInt(KEY_TEST_ALARM_RC, 0)

    fun setTestAlarmRc(c: Context, v: Int) = sp(c).edit().putInt(KEY_TEST_ALARM_RC, v).apply()

    // ── [修复 2026-10-19] 巡检重排防抖 ─────────────────────────

    fun getLastScheduleSig(c: Context): String = sp(c).getString(KEY_LAST_SCHED_SIG, "").orEmpty()

    fun setLastScheduleSig(c: Context, v: String) = sp(c).edit().putString(KEY_LAST_SCHED_SIG, v).apply()

    fun getLastScheduleAt(c: Context): Long = sp(c).getLong(KEY_LAST_SCHED_AT, 0L)

    fun setLastScheduleAt(c: Context, v: Long) = sp(c).edit().putLong(KEY_LAST_SCHED_AT, v).apply()
}
