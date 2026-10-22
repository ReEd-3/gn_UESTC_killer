package com.gnuestc.checkin.engine

import android.content.Context
import android.content.Intent

/**
 * [诊断 2026-10-18] 引擎 → 主应用"事件上报"：
 * 把引擎侧的关键状态写回主应用日志，用于定位"划卡后测试闹钟不触发"时
 * 引擎到底发生了什么（服务被杀？闹钟被清？还是通知被压）。
 */
object EngineEvents {
    private const val MAIN_PACKAGE = "com.gnuestc.checkin"

    const val EVENT_TEST_SCHEDULED = "test_scheduled"
    const val EVENT_SERVICE_CREATED = "service_created"
    const val EVENT_SERVICE_DESTROYED = "service_destroyed"
    const val EVENT_ALARM_FIRED = "alarm_fired"
    const val EVENT_BACKUP_FIRED = "backup_fired"
    // [v7 巡检链] 引擎独立打卡结果
    const val EVENT_CHECKIN_SUCCESS = "checkin_success"
    const val EVENT_CHECKIN_FAILED = "checkin_failed"
    // [保活 2026-10-22] 心跳链发现会话已失效 / 被服务端非登录原因拒绝
    const val EVENT_SESSION_INVALID = "session_invalid"
    const val EVENT_HEARTBEAT_REJECTED = "heartbeat_rejected"

    fun send(context: Context, event: String, detail: String = "") {
        runCatching {
            val intent = Intent("com.gnuestc.checkin.engine.EVENT").apply {
                setClassName(MAIN_PACKAGE, "$MAIN_PACKAGE.EngineEventReceiver")
                putExtra("event", event)
                putExtra("detail", detail)
                putExtra("ts", System.currentTimeMillis())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        }
    }
}
