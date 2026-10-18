package com.gnuestc.checkin.engine

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * [v7 巡检链] 引擎侧打卡巡检链（"不依赖电池豁免"的核心可靠性机制）：
 *  - 打卡窗口内每 5 分钟一个"巡检点"，链式自续（触发→执行→排下一点）
 *  - 任何一次被系统放行即可完成打卡；单点被系统拦截/延后不影响后续巡检点
 *  - 成功后链指向明天；成功/失败均有通知 + 事件回传主应用
 */
object EnginePatrol {

    // [v8 网格] 锚点闹钟 + 双组网格闹钟（“今天剩余” + “明天整窗”，每 5 分钟一个槽位，多点冗余自愈）
    private const val RC_ANCHOR = 9201
    private const val RC_SLOT_A = 9300
    private const val RC_SLOT_B = 9400
    private const val MAX_SLOTS = 40
    private const val INTERVAL_MS = 5 * 60_000L
    private const val MIN_RETRY_GAP_MS = 90_000L

    private const val CHANNEL_ID = "engine_checkin"
    private const val NOTIFY_SUCCESS = 3005
    private const val NOTIFY_FAIL = 3006

    private fun fmtDate() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private fun fmtTime() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun patrolIntent(context: Context): Intent =
        Intent(context, EnginePatrolReceiver::class.java)

    /** 网格槽位专用 Intent（与锚点区分；不同 requestCode 即不同闹钟） */
    private fun slotIntent(context: Context): Intent =
        patrolIntent(context).setAction("com.gnuestc.checkin.engine.PATROL_SLOT")

    private fun anchorPi(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, RC_ANCHOR,
            patrolIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 某天（dayOffset：0=今天 1=明天）的"当天第 N 分钟"对应的绝对时刻 */
    private fun windowBoundary(dayOffset: Int, now: Long, minuteOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(Calendar.MINUTE, minuteOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 计算下一个巡检点：已打卡→明天窗口开始；窗口前→窗口开始；窗口内→+5 分钟；窗口后→明天开始 */
    fun nextTriggerTime(context: Context, now: Long = System.currentTimeMillis()): Long {
        val start = { offset: Int -> windowBoundary(offset, now, EnginePrefs.getWinStart(context)) }
        val end = { offset: Int -> windowBoundary(offset, now, EnginePrefs.getWinEnd(context)) }
        val checkedToday = EnginePrefs.getLastSuccessDate(context) == fmtDate().format(Date(now))
        return when {
            checkedToday -> start(1)
            now < start(0) -> start(0)
            now <= end(0) -> now + INTERVAL_MS
            else -> start(1)
        }
    }

    /**
     * 安排巡检链（[v8 网格] 锚点 + 双组网格，多点冗余、自愈）：
     *  - 锚点：下一个关键点（窗口开始 / 明天窗口开始 / 窗口内下一巡检点）
     *  - 网格 A：“今天剩余”窗口内每 5 分钟一个独立闹钟
     *  - 网格 B：“明天整窗”提前排满（即使今晚全部被系统放倒，明天仍有一整组）
     *  每个闹钟各自独立：某个被拦/延后不影响其它；任何一次跑通即完成打卡
     *  - 未启用/未登录时仅跳过（不破坏已排好的链）；显式关闭时才整体取消
     */
    fun scheduleAll(context: Context) {
        if (!EnginePrefs.isKeepAliveEnabled(context) || EnginePrefs.getCookie(context).isBlank()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 锚点
        armAt(am, anchorPi(context), nextTriggerTime(context))

        val now = System.currentTimeMillis()
        val today = fmtDate().format(Date(now))
        val startToday = windowBoundary(0, now, EnginePrefs.getWinStart(context))
        val endToday = windowBoundary(0, now, EnginePrefs.getWinEnd(context))

        // 网格 A：今天剩余（已打卡则撤销）
        if (EnginePrefs.getLastSuccessDate(context) == today) {
            cancelSlots(context, RC_SLOT_A)
        } else {
            val from = maxOf(now + 3 * 60_000L, startToday)
            if (from <= endToday) armGrid(context, am, RC_SLOT_A, from, endToday)
        }

        // 网格 B：明天整窗预排
        armGrid(
            context, am, RC_SLOT_B,
            windowBoundary(1, now, EnginePrefs.getWinStart(context)),
            windowBoundary(1, now, EnginePrefs.getWinEnd(context))
        )
    }

    /** 取消全部巡检闹钟（显式关闭服务时调用） */
    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(anchorPi(context)) }
        cancelSlots(context, RC_SLOT_A)
        cancelSlots(context, RC_SLOT_B)
    }

    private fun armAt(am: AlarmManager, pi: PendingIntent, at: Long) {
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    private fun armGrid(context: Context, am: AlarmManager, base: Int, fromMillis: Long, endMillis: Long) {
        var t = fromMillis
        var idx = 0
        while (t <= endMillis && idx < MAX_SLOTS) {
            val pi = PendingIntent.getBroadcast(
                context, base + idx, slotIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            armAt(am, pi, t)
            t += INTERVAL_MS
            idx++
        }
    }

    private fun cancelSlots(context: Context, base: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_SLOTS) {
            val pi = PendingIntent.getBroadcast(
                context, base + i, slotIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            runCatching { am.cancel(pi) }
            runCatching { pi.cancel() }
        }
    }

    /** 执行一轮巡检（网络同步执行，请在后台线程调用；执行后会续排下一环） */
    fun executeNow(context: Context) {
        if (!EnginePrefs.isKeepAliveEnabled(context)) return
        val cookie = EnginePrefs.getCookie(context)
        if (cookie.isBlank()) return

        val now = System.currentTimeMillis()
        val today = fmtDate().format(Date(now))

        // 已成功 → 链指向明天
        if (EnginePrefs.getLastSuccessDate(context) == today) {
            scheduleAll(context)
            return
        }
        // 不在窗口内不尝试：时段外提交不生成记录，白白消耗"提交配额"（历史教训）
        val start = windowBoundary(0, now, EnginePrefs.getWinStart(context))
        val end = windowBoundary(0, now, EnginePrefs.getWinEnd(context))
        if (now < start || now > end) {
            scheduleAll(context)
            return
        }
        // 节流：90 秒内不重复尝试（防止频繁打开应用反复提交）
        if (now - EnginePrefs.getLastAttemptAt(context) < MIN_RETRY_GAP_MS) {
            scheduleAll(context)
            return
        }
        EnginePrefs.setLastAttemptAt(context, now)

        // [v8] 巡检路径顺便保活常驻服务（被系统停掉后自动拉起；exact alarm 广播允许启动 FGS）
        if (!EngineKeepAliveService.running) EngineKeepAliveService.start(context)

        val api = EngineCheckin(cookie)
        var ok = false
        var message: String
        try {
            val status = api.getToday().optJSONObject("data")?.optString("recordStatusCode").orEmpty()
            message = when {
                status == "CHECKED_IN" -> {
                    ok = true; "已完成（服务端确认）"
                }
                status.isEmpty() || status == "PENDING" -> {
                    val resp = api.checkin(
                        EnginePrefs.getLat(context),
                        EnginePrefs.getLng(context),
                        EngineCheckin.REVERSE_GEOCODE
                    )
                    when (resp.optInt("code")) {
                        0 -> if (api.verifyCheckedIn()) { ok = true; "打卡成功" }
                             else "提交成功但服务端未记录，将继续巡检"
                        4006 -> if (api.verifyCheckedIn()) { ok = true; "已完成（服务端判定）" }
                                else "判定重复但未记录，将继续巡检"
                        else -> "打卡失败：${resp.optString("msg")}"
                    }
                }
                else -> {
                    ok = true; "状态 $status，跳过打卡"
                }
            }
        } catch (e: Exception) {
            message = "异常：${e.message}"
        }

        if (ok) {
            EnginePrefs.setLastSuccessDate(context, today)
            EnginePrefs.setLastFailSig(context, "")
            notifyResult(context, success = true, message)
            EngineEvents.send(context, EngineEvents.EVENT_CHECKIN_SUCCESS, message)
        } else {
            // 同样的失败原因只提醒/上报一次，避免窗口内每 5 分钟刷屏
            if (EnginePrefs.getLastFailSig(context) != message) {
                EnginePrefs.setLastFailSig(context, message)
                notifyResult(context, success = false, message)
                EngineEvents.send(context, EngineEvents.EVENT_CHECKIN_FAILED, message)
            }
        }
        scheduleAll(context)
    }

    private fun notifyResult(context: Context, success: Boolean, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "打卡结果", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val now = fmtTime().format(Date())
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (success) "打卡引擎 · 打卡成功" else "打卡引擎 · 打卡未成功")
            .setContentText(if (success) "$now $message（后台巡检）" else "$message —— 将继续自动重试")
            .setAutoCancel(true)
            .setOnlyAlertOnce(!success)
            .build()
        if (success) nm.cancel(NOTIFY_FAIL)
        nm.notify(if (success) NOTIFY_SUCCESS else NOTIFY_FAIL, n)
    }
}
