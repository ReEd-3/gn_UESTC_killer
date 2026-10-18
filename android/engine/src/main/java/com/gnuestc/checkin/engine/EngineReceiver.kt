package com.gnuestc.checkin.engine

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [双包 2026-10-18] 引擎控制入口：接收主应用（签名权限保护）发来的指令。
 *
 * 目前支持的指令：
 *  - ping            → 回一条「引擎已就绪」通知（验证连通）
 *  - schedule_test   → 安排一个测试闹钟（默认 2 分钟后触发，验证"防划卡片"）
 *
 * 说明：主应用用显式广播 + FLAG_INCLUDE_STOPPED_PACKAGES 发指令，
 * 即使引擎刚安装、从未被打开过，也能被拉起执行。
 */
class EngineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_CMD)) {
            "ping" -> notify(
                context, NOTIFY_PING,
                "后台引擎已就绪 ✓", "接通正常，可继续发送测试闹钟"
            )
            "schedule_test" -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 2).coerceIn(1, 120)
                val at = System.currentTimeMillis() + minutes * 60_000L
                scheduleAlarm(context, at)
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
                notify(context, NOTIFY_SCHEDULED, "测试闹钟已安排", "将于 $fmt 触发 —— 可在此之前划掉主应用")
            }
        }
    }

    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, EngineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (t: Throwable) {
            // 精确闹钟权限异常时回退为非精确闹钟（仍能触发，只是可能略延迟）
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "引擎状态", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // 无通知权限时静默（BootstrapActivity 会申请）
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("打卡引擎 · $title")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL_ID = "engine_status"
        private const val NOTIFY_PING = 3001
        private const val NOTIFY_SCHEDULED = 3002
    }
}
