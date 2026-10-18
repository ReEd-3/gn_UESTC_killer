package com.gnuestc.checkin.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * [双包 2026-10-18] 引擎闹钟触发：
 * 当前用于"防划卡片"实测验证 —— 只要此通知能弹出，
 * 就证明引擎在主应用被划掉后依然存活并按点执行。
 */
class EngineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "引擎闹钟", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("打卡引擎 · 测试闹钟已触发")
            .setContentText("触发时间 $now —— 引擎存活（主应用被清理也不受影响）")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "engine_alarm"
        private const val NOTIFY_ID = 3003
    }
}
