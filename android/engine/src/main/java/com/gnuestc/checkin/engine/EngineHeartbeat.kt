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

/**
 * [保活 2026-10-22] 会话心跳链（方案 A）。
 *
 * 背景（真机实测）：登录成功后会话本身是有效的，但若长时间（实测约 20 小时）不发任何请求，
 * 服务端会判定会话「登录无效或已过期」→ 打卡窗口到来时已经无法提交，用户被迫重新登录。
 *
 * 做法：一条与巡检链完全独立的闹钟链，每隔 30 分钟发一次只读请求 GET /today：
 *  - 只读、无副作用（实测 /today 不校验来源），不会产生打卡记录、不消耗提交配额
 *  - 请求成功即刷新服务端会话的活跃时间，窗口到来时打卡才不会撞上失效
 *  - 明确返回登录失效 → 停链 + 通知用户重新登录（并把事件上报主应用）
 *  - 网络异常等临时错误 → 不算失效，继续下一次（链条自续，不中断）
 *
 * 为什么必须独立于巡检链：巡检链只在打卡窗口内活跃（窗口外要等到明天窗口开始），
 * 而会话恰恰是在漫长的窗口外时段失效的。
 */
object EngineHeartbeat {

    private const val RC_HEARTBEAT = 9500

    /** 心跳间隔：先用 30 分钟跑一晚观察，确认服务端是滑动过期后再考虑放宽 */
    private const val INTERVAL_MS = 30 * 60_000L

    private const val CHANNEL_ID = "engine_status"
    private const val NOTIFY_EXPIRED = 3007

    private fun intent(context: Context): Intent =
        Intent(context, EngineHeartbeatReceiver::class.java)
            .setAction("com.gnuestc.checkin.engine.HEARTBEAT")

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, RC_HEARTBEAT, intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 安排下一次心跳（常量间隔，链式自续；重复调用只会覆盖同一条闹钟） */
    fun schedule(context: Context) {
        if (!EnginePrefs.isKeepAliveEnabled(context)) return
        if (EnginePrefs.getCookie(context).isBlank()) return
        // 已判定失效就不必再心跳（等用户重新登录后由 sync 重新排链）
        if (!EnginePrefs.isSessionValid(context)) return
        val at = System.currentTimeMillis() + INTERVAL_MS
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        // [同巡检链] 优先"闹钟级"：国产 ROM 的省电策略对普通精确闹钟会大幅推迟
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi) }
            .onFailure {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            }
    }

    /** 撤掉心跳链（服务关闭 / 会话失效时调用） */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, RC_HEARTBEAT, intent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        runCatching { am.cancel(pi) }
        runCatching { pi.cancel() }
    }

    /** 执行一次心跳（网络同步执行，请在后台线程调用），结束后自行安排下一次 */
    fun beat(context: Context) {
        if (!EnginePrefs.isKeepAliveEnabled(context)) return
        val cookie = EnginePrefs.getCookie(context)
        if (cookie.isBlank()) return
        if (!EnginePrefs.isSessionValid(context)) {
            cancel(context)
            return
        }
        // 顺手保活常驻服务（心跳广播本身也带临时白名单，可合法启动前台服务）
        if (!EngineKeepAliveService.running) EngineKeepAliveService.start(context)

        var keepGoing = true
        try {
            val resp = EngineCheckin(cookie).getToday()
            val code = resp.optInt("code", -1)
            val msg = resp.optString("msg")
            if (code != 0) {
                if (isSessionInvalid(msg)) {
                    keepGoing = false
                    EnginePrefs.setSessionValid(context, false)
                    // 会话已不可用：巡检链也没有意义，一并撤掉（避免窗口内反复 POST 失败）
                    EnginePatrol.cancelAll(context)
                    EngineEvents.send(context, EngineEvents.EVENT_SESSION_INVALID, msg)
                    notifyExpired(context, msg)
                } else {
                    // 其它业务拒绝（如来源校验）：会话未必失效，不在这里下结论，继续心跳
                    EngineEvents.send(context, EngineEvents.EVENT_HEARTBEAT_REJECTED, msg)
                }
            }
        } catch (_: Exception) {
            // 网络异常/超时：属于临时错误，不算会话失效，照常排下一次
        }
        if (keepGoing) schedule(context)
    }

    /** 服务端明确表示会话失效（与 EnginePatrol 的口径保持一致） */
    private fun isSessionInvalid(msg: String): Boolean =
        msg.contains("登录无效") || msg.contains("已过期") || msg.contains("未登录") ||
            msg.contains("请先登录") || msg.contains("会话已失效")

    /** 会话失效提醒（会打扰用户，但此时不提醒就只能等打卡失败才发现） */
    private fun notifyExpired(context: Context, msg: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "引擎状态", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = runCatching {
            (context.packageManager.getLaunchIntentForPackage("com.gnuestc.checkin")
                ?: Intent().setClassName("com.gnuestc.checkin", "com.gnuestc.checkin.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()
        val contentPi = openApp?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_checkin)
            .setLargeIcon(EngineIcon.mainAppLarge(context))
            .setContentTitle("登录已失效")
            .setContentText("后台保活已停止，请打开应用重新登录（$msg）")
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        runCatching { nm.notify(NOTIFY_EXPIRED, n) }
    }
}
