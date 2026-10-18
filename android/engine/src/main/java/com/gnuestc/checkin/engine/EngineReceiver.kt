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
                val exact = scheduleAlarm(context, at)
                // [v5] 双通道：再挂一条"常驻备份"内存定时器（系统闹钟被清/被延后时的救命通道）
                val backup = EngineKeepAliveService.scheduleTestBackup(context, at)
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
                val mode = if (exact) "精确模式" else "兼容模式（可能延迟）"
                val backupText = if (backup) "常驻备份已挂" else "常驻备份未挂（常驻服务未运行）"
                notify(context, NOTIFY_SCHEDULED, "测试闹钟已安排", "将于 $fmt 触发 · $mode · $backupText")
                // [v6] 事件上报：让主应用日志也能看到引擎受理情况
                EngineEvents.send(context, EngineEvents.EVENT_TEST_SCHEDULED, "$mode · $backupText")
                // [v9] 存活心跳：每秒刷新"已安排"通知（秒数在跳=引擎活着；冻结=停跳）
                if (EnginePrefs.isKeepAliveEnabled(context) && !EngineKeepAliveService.running) {
                    EngineKeepAliveService.start(context)
                }
                EngineKeepAliveService.startTestTicker(context, at, "$mode · $backupText")
            }
            // [双包 2026-10-18] 主应用同步状态：开关 + 下次打卡时间
            // [v7 巡检链] 扩展：会话/坐标/打卡窗口一并同步，引擎据此具备独立打卡能力
            "sync" -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                val nextText = intent.getStringExtra(EXTRA_NEXT_TEXT).orEmpty()
                EnginePrefs.setKeepAliveEnabled(context, enabled)
                EnginePrefs.setNextText(context, nextText)
                intent.getStringExtra(EXTRA_COOKIE)?.let { EnginePrefs.setCookie(context, it) }
                if (intent.hasExtra(EXTRA_LAT)) {
                    EnginePrefs.setLat(context, intent.getDoubleExtra(EXTRA_LAT, EnginePrefs.getLat(context)))
                }
                if (intent.hasExtra(EXTRA_LNG)) {
                    EnginePrefs.setLng(context, intent.getDoubleExtra(EXTRA_LNG, EnginePrefs.getLng(context)))
                }
                if (intent.hasExtra(EXTRA_WIN_START)) {
                    EnginePrefs.setWinStart(context, intent.getIntExtra(EXTRA_WIN_START, EnginePrefs.getWinStart(context)))
                }
                if (intent.hasExtra(EXTRA_WIN_END)) {
                    EnginePrefs.setWinEnd(context, intent.getIntExtra(EXTRA_WIN_END, EnginePrefs.getWinEnd(context)))
                }
                if (enabled) {
                    EngineKeepAliveService.start(context)
                    // [v12] 若广播唤不醒（后台启动限制），3 秒后用 exact alarm 再试一次（自带临时白名单）
                    EnginePatrol.scheduleRevive(context)
                    // [v7/v8] 安排巡检链（窗口内每 5 分钟一个巡检点，网格冗余）
                    EnginePatrol.scheduleAll(context)
                } else {
                    EngineKeepAliveService.stop(context)
                    EnginePatrol.cancelAll(context)
                }
            }
            // [v7 巡检链] 主应用回到前台时的立即补巡检（90 秒节流在引擎侧）
            "patrol_now" -> {
                val pending = goAsync()
                Thread {
                    try {
                        EnginePatrol.executeNow(context)
                    } finally {
                        runCatching { pending.finish() }
                    }
                }.start()
            }
        }
    }

    /** 安排测试闹钟；@return 是否使用了"精确闹钟"（false = 已降级为可能延迟的兼容模式） */
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, EngineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            true
        } catch (t: Throwable) {
            // 精确闹钟权限异常时回退为非精确闹钟（仍能触发，只是可能略延迟）
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            false
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
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_NEXT_TEXT = "next_text"
        // [v7 巡检链] 打卡执行所需参数的同步字段
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_WIN_START = "win_start"
        const val EXTRA_WIN_END = "win_end"
        private const val CHANNEL_ID = "engine_status"
        private const val NOTIFY_PING = 3001

        // [v9] 心跳刷新需要从服务侧复用同一通知位
        const val NOTIFY_SCHEDULED = 3002
    }
}
