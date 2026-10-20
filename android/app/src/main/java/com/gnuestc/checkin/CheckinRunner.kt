package com.gnuestc.checkin

import android.content.Context

/**
 * 单次打卡尝试的共用逻辑：闹钟接收器、主界面「测试」按钮、打开应用时的自动补打都走这里。
 *
 * [修复 2026-10-17] 流程精简：先查今日状态并立即打卡（关键路径），
 * 打卡点/时间窗口等附加同步挪到打卡之后再执行（尽力而为，不影响打卡结果）。
 */
object CheckinRunner {

    /** [修复 2026-10-19] 视为"今日已完成、无需再打卡"的服务端状态白名单；其余未知状态将继续重试 */
    private val DONE_STATUSES = setOf(
        "INSTRUCTOR_REPORTED", "STUDENT_REPORTED", "REPORTED", "EXPIRED", "CLOSED"
    )

    /** 关键路径：查询今日状态 → 必要时立即打卡；返回结果文案 */
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
            // [修复 2026-10-17] 原实现在此先做了两次附加同步（打卡点/时间窗口），
            // 现将它们移到打卡成功之后再执行（
            //     RemoteSync.refreshLocation(context, api)
            //     RemoteSync.refreshSchedule(context, api)
            // ），缩短关键路径耗时
            val today = api.getToday()
            val status = today.optJSONObject("data")?.optString("recordStatusCode").orEmpty()
            when {
                status == "CHECKED_IN" -> {
                    Prefs.markCheckedToday(context)
                    "今日已打卡，无需操作"
                }
                status.isEmpty() || status == "PENDING" -> {
                    // 使用用户配置的「脚本定位」（默认 = 打卡点本身）
                    val resp = api.checkin(
                        Prefs.getLocationLat(context),
                        Prefs.getLocationLng(context),
                        CheckinApi.REVERSE_GEOCODE
                    )
                    when (resp.optInt("code")) {
                        0 -> {
                            // [修复 2026-10-18] 二次确认：实测"接口返回 code=0 ≠ 学校真的记上了卡"
                            // （不在有效时段内提交时同样返回成功，但不会形成打卡记录）
                            if (verifyCheckedIn(api)) {
                                Prefs.markCheckedToday(context)
                                "打卡成功"
                            } else {
                                "打卡未生效：服务端未记录（可能不在学校有效时间内），将继续重试"
                            }
                        }
                        4006 -> {
                            // [修复 2026-10-18] 4006 同样不可信：实测白天一次"假成功"提交后，
                            // 再次提交会回 4006（重复判定），但服务端并未生成打卡记录（/today 仍 PENDING）。
                            // 因此必须先复查，只有确认 CHECKED_IN 才算已完成。
                            if (verifyCheckedIn(api)) {
                                Prefs.markCheckedToday(context)
                                "今日已完成打卡（服务端判定）"
                            } else {
                                "打卡未生效：服务端判定重复但未记录，将继续重试"
                            }
                        }
                        else -> "打卡失败：${resp.optString("msg")}"
                    }
                }
                // [修复 2026-10-19] 仅"明确的已完成类状态"视为终态；未知状态不再标记完成（继续按窗口重试），
                // 避免服务端出现新状态（如驳回/需补报）时当天被误判为"已完成"而不再尝试
                status in DONE_STATUSES -> {
                    Prefs.markCheckedToday(context)
                    "当前状态 $status，跳过打卡"
                }
                else -> "未知状态 $status，将继续重试"
            }
        } catch (e: Exception) {
            // 网络异常/解析失败/服务端返回非预期内容时走这里（会话失效通常为 JSON msg，走“打卡失败：…”分支）
            "打卡异常：${e.message}"
        }
        Prefs.setLastResult(context, result)
        LogStore.append(context, "[打卡] $result")
        return result
    }

    /** [修复 2026-10-18] 提交后复查今日状态，确认服务端真的记录了打卡（最多查 4 次，间隔 2 秒） */
    private fun verifyCheckedIn(api: CheckinApi): Boolean {
        repeat(4) { attempt ->
            val ok = runCatching {
                api.getToday().optJSONObject("data")?.optString("recordStatusCode") == "CHECKED_IN"
            }.getOrDefault(false)
            if (ok) return true
            if (attempt < 3) Thread.sleep(2000)
        }
        return false
    }

    /** 附加同步（打卡点 / 时间窗口）：尽力而为，失败静默 */
    fun syncExtras(context: Context) {
        val cookie = Prefs.getCookie(context)
        if (cookie.isBlank()) return
        runCatching {
            val api = CheckinApi(cookie)
            RemoteSync.refreshLocation(context, api)
            RemoteSync.refreshSchedule(context, api)
        }
    }

    /** 打卡后统一重排闹钟：终态 → 明天窗口；否则 → 窗口内 5 分钟后重试 */
    fun rescheduleAfterRun(context: Context, result: String) {
        if (isFinal(result)) AlarmScheduler.scheduleDaily(context)
        else AlarmScheduler.scheduleRetry(context)
    }

    /** 结果是否属于「不需要再重试」 */
    fun isFinal(result: String): Boolean =
        result.contains("成功") || result.contains("已打卡") ||
            result.contains("已完成") || result.contains("跳过") || result.contains("未登录") ||
            // [修复 2026-10-19] 会话失效（服务端"登录无效或已过期"）重试无法恢复：
            // 归为终态，停止每 5 分钟重试并提示重新登录（此前会无限重试）
            result.contains("登录无效") || result.contains("已过期") ||
            // [修复 2026-10-20] 服务端来源校验（"请从微信小程序重新进入打卡应用后提交"）等硬拒绝：
            // 重试同样会被拒，停止重试避免空耗提交次数/触发风控
            isNonRetryableRejection(result)

    /** [修复 2026-10-20] 服务端"重试也没意义"的拒绝（来源校验等） */
    fun isNonRetryableRejection(result: String): Boolean =
        result.contains("微信小程序") || result.contains("重新进入") ||
            result.contains("无权限") || result.contains("非法")

    /**
     * [文案 2026-10-19] 「用户向」文案：通知 / 首页「最近打卡」/ 前台 Toast 使用；
     * 运行日志与内部记录仍保留原始结果（技术细节供排查）。
     */
    fun userText(result: String): String = when {
        result.contains("已恢复") -> result // 对账提示本就是用户向文案
        // [修复 2026-10-19] 服务端会话失效（"登录无效或已过期"）：提示重新登录
        result.contains("登录无效") || result.contains("已过期") || result.contains("未登录") ->
            "登录已过期，请重新登录"
        // [修复 2026-10-20] 服务端来源校验等硬拒绝：告诉用户去小程序完成（需在"失败"分支之前）
        isNonRetryableRejection(result) -> "打卡被服务端拒绝，请在小程序「晚安成电」中完成打卡"
        result.contains("判定重复") -> "打卡未成功（重复提交），稍后自动重试"
        // [修复 2026-10-19] 服务端未知状态：按"稍后重试"展示，避免把内部状态码直接抛给用户
        result.contains("未知状态") -> "打卡未成功，稍后自动重试"
        result.contains("未生效") -> "打卡未成功：服务端未记录，稍后自动重试"
        result.contains("失败") || result.contains("异常") -> "打卡失败，稍后自动重试"
        result.contains("跳过") -> "今日无需打卡"
        result.contains("成功") || result.contains("已打卡") || result.contains("已完成") -> "今天的打卡已完成"
        else -> result
    }
}
