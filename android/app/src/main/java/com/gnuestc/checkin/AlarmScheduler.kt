package com.gnuestc.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 用精确闹钟做定时，不依赖进程常驻；系统到点会拉起 CheckinReceiver */
object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    /** 每天 21:05 触发（打卡窗口 21:00–23:30） */
    private const val TRIGGER_HOUR = 21
    private const val TRIGGER_MINUTE = 5

    /** 失败重试间隔与窗口截止（超过则排到第二天） */
    private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L
    private const val WINDOW_END_HOUR = 23
    private const val WINDOW_END_MINUTE = 20

    fun scheduleDaily(context: Context) = set(context, nextDailyTrigger())

    /** 失败重试：还在窗口内则 5 分钟后重试，否则直接排到明天 */
    fun scheduleRetry(context: Context) {
        val now = System.currentTimeMillis()
        val windowEnd = todayAt(WINDOW_END_HOUR, WINDOW_END_MINUTE)
        val next = if (now + RETRY_INTERVAL_MS <= windowEnd) now + RETRY_INTERVAL_MS else nextDailyTrigger()
        set(context, next)
    }

    /**
     * App 启动/登录成功时调用：确保总是有一个合理的待触发时段——
     * 未到 21:05 排今天；窗口内（含已过点）1 分钟后尽快补；窗口结束排明天。
     */
    /** 停止服务：取消待触发闹钟 */
    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
        Prefs.setNextTrigger(context, 0L)
    }

    fun ensureScheduled(context: Context) {
        if (!Prefs.isServiceEnabled(context)) return
        if (Prefs.getCookie(context).isBlank()) return
        val now = System.currentTimeMillis()
        val next = when {
            now < todayAt(TRIGGER_HOUR, TRIGGER_MINUTE) -> todayAt(TRIGGER_HOUR, TRIGGER_MINUTE)
            now <= todayAt(WINDOW_END_HOUR, WINDOW_END_MINUTE) -> now + 60_000L
            else -> todayAt(TRIGGER_HOUR, TRIGGER_MINUTE) + 24L * 60 * 60 * 1000
        }
        set(context, next)
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    fun nextTriggerTimeText(context: Context): String {
        val t = Prefs.getNextTrigger(context)
        if (t <= 0L) return "未安排"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }

    private fun set(context: Context, triggerAtMillis: Long) {
        alarmManager(context).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(context)
        )
        Prefs.setNextTrigger(context, triggerAtMillis)
    }

    private fun nextDailyTrigger(): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, TRIGGER_HOUR)
            set(Calendar.MINUTE, TRIGGER_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    private fun todayAt(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CheckinReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
