package com.gnuestc.checkin

import android.content.Context

/** 单次打卡尝试的共用逻辑：闹钟接收器与主界面「测试」按钮都走这里 */
object CheckinRunner {

    fun run(context: Context): String {
        val cookie = Prefs.getCookie(context)
        if (cookie.isBlank()) {
            val msg = "未登录：请先完成登录"
            Prefs.setLastResult(context, msg)
            LogStore.append(context, "[打卡] $msg")
            return msg
        }

        val result = try {
            val api = CheckinApi(cookie)
            // 刷新本账号打卡点与时间窗口（服务端下发；失败不影响打卡流程）
            RemoteSync.refreshLocation(context, api)
            RemoteSync.refreshSchedule(context, api)
            val today = api.getToday()
            val status = today.optJSONObject("data")?.optString("recordStatusCode").orEmpty()
            when {
                status == "CHECKED_IN" -> "今日已打卡，无需操作"
                status.isEmpty() || status == "PENDING" -> {
                    // 使用用户配置的「脚本定位」（默认 = 打卡点本身）
                    val resp = api.checkin(
                        Prefs.getLocationLat(context),
                        Prefs.getLocationLng(context),
                        CheckinApi.REVERSE_GEOCODE
                    )
                    when (resp.optInt("code")) {
                        0 -> "打卡成功"
                        4006 -> "今日已完成打卡（服务端判定）"
                        else -> "打卡失败：${resp.optString("msg")}"
                    }
                }
                // INSTRUCTOR_REPORTED / STUDENT_REPORTED 等情况不主动打卡，避免覆盖已有登记
                else -> "当前状态 $status，跳过打卡"
            }
        } catch (e: Exception) {
            // 会话失效时接口会返回登录页 HTML，JSON 解析失败也走这里
            "打卡异常：${e.message}"
        }
        Prefs.setLastResult(context, result)
        LogStore.append(context, "[打卡] $result")
        return result
    }

    /** 结果是否属于「不需要再重试」 */
    fun isFinal(result: String): Boolean =
        result.contains("成功") || result.contains("已打卡") ||
            result.contains("已完成") || result.contains("跳过") || result.contains("未登录")
}
