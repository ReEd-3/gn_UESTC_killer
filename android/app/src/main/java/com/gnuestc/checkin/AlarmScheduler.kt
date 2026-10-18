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

    /**
     * 失败重试：仅在打卡窗口内安排 5 分钟后重试；未到窗口开始则等到窗口开始。
     * [修复 2026-10-18] 原实现只判断窗口结束、不判断窗口开始：窗口外（如手动测试）失败后
     * 会每 5 分钟持续尝试，且用户把窗口推迟也停不下来。
     */
    fun scheduleRetry(context: Context) {
        val now = System.currentTimeMillis()
        val start = todayAtMinute(Prefs.getWindowStartMinute(context))
        val end = todayAtMinute(Prefs.getWindowEndMinute(context))
        val next = when {
            now < start -> start
            now + RETRY_INTERVAL_MS <= end -> now + RETRY_INTERVAL_MS
            else -> nextDailyTrigger(context)
        }
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

    // [修复 2026-10-17] 原实现保留备查（窗口内不看"今日已打卡"、且每次都会 set() 覆盖旧闹钟）：
    // fun ensureScheduled(context: Context) {
    //     if (!Prefs.isServiceEnabled(context)) return
    //     if (Prefs.getCookie(context).isBlank()) return
    //     val now = System.currentTimeMillis()
    //     val start = todayAtMinute(Prefs.getWindowStartMinute(context))
    //     val end = todayAtMinute(Prefs.getWindowEndMinute(context))
    //     val next = when {
    //         now < start -> start
    //         now <= end -> now + 60_000L
    //         else -> start + 24L * 60 * 60 * 1000
    //     }
    //     set(context, next)
    // }
    fun ensureScheduled(context: Context) {
        if (!Prefs.isServiceEnabled(context)) return
        if (Prefs.getCookie(context).isBlank()) return
        val now = System.currentTimeMillis()
        val start = todayAtMinute(Prefs.getWindowStartMinute(context))
        val end = todayAtMinute(Prefs.getWindowEndMinute(context))
        val next = when {
            // [修复 2026-10-18] 今天已完成：直接排明天窗口开始（原实现用 nextDailyTrigger，
            // 在窗口开始前会返回"今天"的开始时间，导致当天再被无意义唤醒一次）
            Prefs.isCheckedToday(context) -> start + 24L * 60 * 60 * 1000
            now < start -> start
            now <= end -> now + 60_000L
            else -> start + 24L * 60 * 60 * 1000
        }
        // [修复 2026-10-17] 不覆盖"更早的待触发闹钟"：此前每次打开应用都会把闹钟推后 1 分钟，
        // 导致"打开 → +1 分钟 → 再打开 → 再 +1 分钟"的循环，闹钟永远差一点没跑
        // [修复 2026-10-18] 保留条件补充"必须仍落在当前窗口内"：否则用户把窗口推迟后，
        // 落在新窗口之外的旧闹钟（如重试链）不会被替换，仍会在窗口外不断尝试
        if (!Prefs.isCheckedToday(context)) {
            val pending = Prefs.getNextTrigger(context)
            if (pending > now && pending <= next && pending >= start && pending <= end) return
        }
        set(context, next)
    }

    /** 当前是否处于今天的打卡窗口内（供"打开应用即补打"判断） */
    fun isInWindowNow(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return now >= todayAtMinute(Prefs.getWindowStartMinute(context)) &&
            now <= todayAtMinute(Prefs.getWindowEndMinute(context))
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    fun nextTriggerTimeText(context: Context): String {
        val t = Prefs.getNextTrigger(context)
        if (t <= 0L) return "未安排"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }

    // [修复 2026-10-17] 原实现保留备查：
    // private fun set(context: Context, triggerAtMillis: Long) {
    //     alarmManager(context).setExactAndAllowWhileIdle(
    //         AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(context)
    //     )
    //     Prefs.setNextTrigger(context, triggerAtMillis)
    // }

    /**
     * [修复 2026-10-17] 优先用 setAlarmClock（系统最高优先级，不受休眠/省电延后；
     * 代价：状态栏会常驻闹钟图标），失败（如未授予精确闹钟权限）则回退 setExactAndAllowWhileIdle。
     */
    private fun set(context: Context, triggerAtMillis: Long) {
        val am = alarmManager(context)
        val pi = pendingIntent(context)
        val prev = Prefs.getNextTrigger(context)
        val ok = runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)), pi)
        }.isSuccess || runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }.isSuccess
        if (!ok) {
            LogStore.append(context, "[系统] 闹钟安排失败（精确闹钟权限未授予）")
        } else if (prev != triggerAtMillis) {
            // [诊断 2026-10-18] 仅当触发时间变化时记录，便于排查"闹钟到底有没有排上"
            LogStore.append(
                context,
                "[系统] 定时任务已安排：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAtMillis))}"
            )
        }
        Prefs.setNextTrigger(context, if (ok) triggerAtMillis else 0L)
    }

    /** setAlarmClock 的"点击闹钟图标打开应用"入口 */
    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE + 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
