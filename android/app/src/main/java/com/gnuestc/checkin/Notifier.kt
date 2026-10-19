package com.gnuestc.checkin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/** 打卡结果通知 */
object Notifier {

    private const val CHANNEL_ID = "checkin_result"
    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "打卡结果", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // [修复 2026-10-18] 无通知权限时把结果写入运行日志，避免打卡结果"静默消失"
            LogStore.append(context, "[通知] 未授予通知权限，未发提醒：$text")
            return
        }

        // [改进 2026-10-18] 中间态（还会自动重试）用独立通知位并"只提醒一次"，避免重试期间反复响铃；
        // 终态（成功 / 已完成 / 跳过等）正常提醒
        val finalResult = CheckinRunner.isFinal(text)
        // [文案 2026-10-19] 通知显示用「用户向」文案；isFinal 判断与日志仍使用原始结果
        val displayText = CheckinRunner.userText(text)
        // [修复 2026-10-19] 点击通知直接打开应用（会话失效时提示“请重新登录”，需可达）
        val contentPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("晚安打卡")
            .setContentText(displayText)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!finalResult)
            .build()
        nm.notify(if (finalResult) NOTIFICATION_ID else NOTIFICATION_ID + 1, notification)
    }
}
