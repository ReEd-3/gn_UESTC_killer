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

    /** 失败重试间隔（触发时间与截止时间以 Prefs 中同步的窗口为准，默认 21:05–23:30） */
    private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    fun scheduleDaily(context: Context) = set(context, nextDailyTrigger(context))

    /** 失败重试：还在窗口内则 5 分钟后重试，否则直接排到明天 */
    fun scheduleRetry(context: Context) {
        val now = System.currentTimeMillis()
        val windowEnd = todayAtMinute(Prefs.getWindowEndMinute(context))
        val next = if (now + RETRY_INTERVAL_MS <= windowEnd) now + RETRY_INTERVAL_MS else nextDailyTrigger(context)
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
        val start = todayAtMinute(Prefs.getWindowStartMinute(context))
        val end = todayAtMinute(Prefs.getWindowEndMinute(context))
        val next = when {
            now < start -> start
            now <= end -> now + 60_000L
            else -> start + 24L * 60 * 60 * 1000
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

    /** 下一次每日触发：今天的窗口开始时间（默认 21:05），已过则明天 */
    private fun nextDailyTrigger(context: Context): Long {
        val target = Calendar.getInstance().apply {
            val m = Prefs.getWindowStartMinute(context)
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    /** 今天某「当天分钟数」（如 21:05 = 1265）对应的时间戳 */
    private fun todayAtMinute(minuteOfDay: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
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
