package com.gnuestc.checkin

import android.content.Context
import org.json.JSONObject

/**
 * 从服务端同步「本账号」相关的个性化配置（打卡点 / 打卡时间窗口）。
 * 全部 runCatching 静默失败，绝不打断登录或打卡主流程。
 */
object RemoteSync {

    /**
     * 打卡点：GET /checkin-location/available-locations
     * data[0] 字段：latitude / longitude / radiusMeter / groupName（服务端按学生/组团下发）
     * @return 是否成功保存
     */
    fun refreshLocation(context: Context, api: CheckinApi): Boolean {
        var saved = false
        runCatching {
            val loc = api.getAvailableLocations()
            if (loc.optInt("code", -1) == 0) {
                val item = loc.optJSONArray("data")?.optJSONObject(0)
                if (item != null) {
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lng = item.optDouble("longitude", Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        Prefs.setDefaultPoint(
                            context, lat, lng,
                            item.optString("groupName"),
                            item.optDouble("radiusMeter", CheckinApi.FENCE_RADIUS_M)
                        )
                        saved = true
                    }
                }
            }
        }
        return saved
    }

    /**
     * 时间窗口：GET /checkin-schedule/effective
     * data 字段：businessDate + studentCheckinStartOffsetMinute / studentCheckinEndOffsetMinute（当天 00:00 起分钟偏移）
     * 打卡触发时间 = 窗口开始 + 5 分钟（与默认 21:05 的节奏一致），重试截止 = 窗口结束
     *
     * [手动设置 2026-10-18] 拆分为两个入口：
     *  - refreshSchedule：后台自动同步（登录 / 打卡时），尊重「手动设置」锁定，不覆盖
     *  - syncScheduleNow：用户在菜单中显式「从服务端同步」，覆盖手动值（解除锁定）
     */
    fun refreshSchedule(context: Context, api: CheckinApi): Boolean =
        applySchedule(context, api, force = false)

    fun syncScheduleNow(context: Context, api: CheckinApi): Boolean =
        applySchedule(context, api, force = true)

    private fun applySchedule(context: Context, api: CheckinApi, force: Boolean): Boolean {
        // [手动设置 2026-10-18] 手动锁定中且非显式同步 → 跳过，保护用户设置
        if (!force && Prefs.isWindowManual(context)) return false
        var saved = false
        runCatching {
            val resp = api.getEffectiveSchedule()
            if (resp.optInt("code", -1) == 0) {
                val data: JSONObject = resp.optJSONObject("data") ?: return@runCatching
                val start = data.optInt("studentCheckinStartOffsetMinute", -1)
                val end = data.optInt("studentCheckinEndOffsetMinute", -1)
                if (start in 0 until end && end <= 24 * 60 && start + 5 < end) {
                    // [手动设置 2026-10-18] 原实现：Prefs.setWindow(context, start + 5, end, synced = true)
                    Prefs.setWindowServer(context, start + 5, end)
                    saved = true
                }
            }
        }
        return saved
    }
}
