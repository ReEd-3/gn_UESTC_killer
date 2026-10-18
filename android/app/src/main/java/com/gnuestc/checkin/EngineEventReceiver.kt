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
            else -> return
        }
        LogStore.append(context, text)
    }
}
