package com.gnuestc.checkin.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * [双包 2026-10-18] 引擎自己的常驻前台服务：把"打卡引擎"亮在系统面前——
 * 低优先级常驻通知 + 前台服务优先级，降低引擎被系统静默回收的概率。
 *
 * 为什么放在引擎侧：主应用会被"划卡片"强停（常驻也没用），而引擎不在最近任务
 * 出现（划不到），此常驻通知即引擎存活状态的唯一可视入口。
 * 与主应用的「后台自动打卡服务」开关联动（主应用经 sync 指令同步）。
 */
class EngineKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        // [Android 14+] 前台服务需声明类型：本服务用 specialUse（守护定时任务）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "engine_keepalive"
        private const val NOTIFICATION_ID = 3010
        private const val MAIN_PACKAGE = "com.gnuestc.checkin"

        /** 服务是否正在运行（进程内标记） */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, EngineKeepAliveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, EngineKeepAliveService::class.java))
            }
            running = false
        }

        private fun buildNotification(context: Context): Notification {
            // 点击常驻通知 → 打开主应用
            // [修复 2026-10-18] 原实现的兜底是"空 Intent"，点击无任何反应；
            // 改为显式组件兜底（并加 NEW_TASK），不依赖包可见性查询结果
            val openIntent = context.packageManager.getLaunchIntentForPackage(MAIN_PACKAGE)
                ?: Intent().setClassName(MAIN_PACKAGE, "$MAIN_PACKAGE.MainActivity")
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val openApp = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val next = EnginePrefs.getNextText(context)
            val text = if (next.isBlank()) {
                "后台组件守护中 · 点击打开应用"
            } else {
                "下次打卡：$next · 点击打开应用"
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("打卡引擎运行中")
                .setContentText(text)
                .setOngoing(true) // 不可划掉
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "服务状态",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
