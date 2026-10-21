package com.gnuestc.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [诊断 2026-10-18] 接收引擎上报的运行事件并写入应用日志：
 * 服务创建/被停止、系统闹钟触发、常驻备份触发。
 * 仅同签名的引擎可调用（CONTROL_ENGINE 签名级权限保护）。
 */
class EngineEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = intent.getStringExtra("event") ?: return
        val detail = intent.getStringExtra("detail").orEmpty()
        val ts = intent.getLongExtra("ts", System.currentTimeMillis())
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
        val suffix = if (detail.isNotBlank()) "（$detail）" else ""
        val text = when (event) {
            "test_scheduled" -> "[引擎] 已受理测试闹钟$suffix（$fmt）"
            "service_created" -> "[引擎] 常驻服务已启动（$fmt）"
            "service_destroyed" -> "[引擎] ⚠ 常驻服务被系统停止（$fmt）"
            "alarm_fired" -> "[引擎] 系统闹钟已触发（$fmt）"
            "backup_fired" -> "[引擎] 常驻备份已触发（$fmt）"
            // [v7 巡检链] 引擎独立打卡结果 → 同步主应用状态
            "checkin_success" -> {
                Prefs.markCheckedToday(context)
                // [修复 2026-10-21] 引擎打卡成功 → 会话仍有效
                Prefs.setSessionValid(context, true)
                // [review 2026-10-21] 同步写"最近打卡"：此前只标完成不写文案，
                // 导致引擎后台打卡成功后首页仍停在上一轮的"打卡未成功，稍后自动重试"
                //（probeServerState 因本地标记已为 true 也不会纠正）
                Prefs.setLastResult(context, "后台巡检打卡成功")
                // [修复 2026-10-19] 立即把主应用的重试链指向明天：
                // 否则引擎已打完卡时，主应用还要等下一轮重试/补打才发现已完成
                runCatching { AlarmScheduler.scheduleDaily(context) }
                "[引擎] 后台巡检打卡成功（$fmt）"
            }
            "checkin_failed" -> {
                // [修复 2026-10-19] 会话失效时引擎已暂停巡检，日志如实说明（需重新登录）
                val expired = detail.contains("登录无效") || detail.contains("已过期") ||
                    detail.contains("未登录")
                // [修复 2026-10-21] 引擎侧发现的会话失效也要反映到可界面：账号状态置为登录已失效
                // 但若该事件早于当前 cookie 写入时间（旧会话的迟到失败），忽略——
                // 否则刚重新登录成功就会被旧事件覆盖成“登录已失效”
                if (expired && ts >= Prefs.getCookieAt(context)) Prefs.setSessionValid(context, false)
                val tail = if (expired) "→ 已暂停今日/明日巡检，请重新登录" else "→ 将继续自动重试"
                "[引擎] 打卡未成功：$detail（$fmt）$tail"
            }
            else -> return
        }
        LogStore.append(context, text)
    }
}
