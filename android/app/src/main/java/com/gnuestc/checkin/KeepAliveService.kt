package com.gnuestc.checkin

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
 * [新增 2026-10-18] 前台常驻服务：把打卡调度"亮"在系统面前——
 * 低优先级常驻通知 + 前台服务优先级，降低被系统静默回收的概率。
 * 与「后台自动打卡服务」开关联动：开=常驻（需已登录），关=撤下。
 * 注意：是否能扛住"划掉最近任务卡片"取决于 ROM 行为，需真机实测。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        // [Android 14+] 前台服务需声明类型：本服务用 specialUse（保持定时任务可靠）
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
        private const val CHANNEL_ID = "service_status"
        private const val NOTIFICATION_ID = 1999

        /** 服务是否正在运行（进程内标记，用于决定"刷新通知文案"是否生效） */
        @Volatile
        var running = false
            private set

        /** 按「服务开关 + 已登录」状态同步常驻服务：满足则启动，否则撤下 */
        fun syncKeepAlive(context: Context) {
            val shouldRun = Prefs.isServiceEnabled(context) && Prefs.getCookie(context).isNotBlank()
            if (shouldRun) start(context) else stop(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveService::class.java))
            }
            running = false
        }

        /** 刷新常驻通知文案（"下次打卡"时间变化时调用；服务未运行则空操作） */
        fun refresh(context: Context) {
            if (!running) return
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(context))
            }
        }

        private fun buildNotification(context: Context): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.keepalive_title))
                .setContentText(
                    context.getString(
                        R.string.keepalive_text_fmt,
                        AlarmScheduler.nextTriggerTimeText(context)
                    )
                )
                .setOngoing(true) // 不可划掉
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
