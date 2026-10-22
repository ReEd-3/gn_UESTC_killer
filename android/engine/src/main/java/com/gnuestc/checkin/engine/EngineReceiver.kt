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
                // [review 2026-10-21] 会话有效性：主应用已判定登录失效时不再排巡检，
                // 否则引擎会在打卡窗口内一轮轮 POST 失败（空耗提交配额、有风控风险）
                val valid = intent.getBooleanExtra(EXTRA_SESSION_VALID, true)
                EnginePrefs.setSessionValid(context, valid)
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
                // [修复 2026-10-19] 主应用今日已完成 → 引擎记录成功日，scheduleAll 随即撤销今日巡检网格
                // [review 2026-10-21] 改为"有该字段就双向同步"：主应用与服务端对账后发现"此前打卡未生效"
                // 会下发 false，此前引擎只认 true → 引擎仍认为今天已完成 → 双保险直接失效
                if (intent.hasExtra(EXTRA_CHECKED)) {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    if (intent.getBooleanExtra(EXTRA_CHECKED, false)) {
                        EnginePrefs.setLastSuccessDate(context, today)
                    } else if (EnginePrefs.getLastSuccessDate(context) == today) {
                        EnginePrefs.setLastSuccessDate(context, "")
                    }
                }
                if (enabled) {
                    // [修复 2026-10-19] 服务已在运行则不再重复 start、也不再挂"3 秒复活"闹钟：
                    // 频繁 sync 时避免反复拉起服务、反复安排唤醒（顺带消除"打开应用即触发一次引擎巡检提交"的副作用）；
                    // 仅当进程被冻结/回收（running=false）时才需要 start + 复活兜底
                    if (!EngineKeepAliveService.running) {
                        EngineKeepAliveService.start(context)
                        // [v12] 若广播唤不醒（后台启动限制），3 秒后用 exact alarm 再试一次（自带临时白名单）
                        EnginePatrol.scheduleRevive(context)
                    }
                    // [v7/v8] 安排巡检链（窗口内每 5 分钟一个巡检点，网格冗余）；scheduleAll 自带签名防抖
                    // [review 2026-10-21] 会话失效时撤掉巡检链（服务保留，通知上会提示重新登录）
                    if (valid) {
                        EnginePatrol.scheduleAll(context)
                        // [保活 2026-10-22] 会话有效：确保心跳链在跑（窗口外的时段靠它维持会话）
                        EngineHeartbeat.schedule(context)
                    } else {
                        EnginePatrol.cancelAll(context)
                        EngineHeartbeat.cancel(context)
                    }
                    // [review 2026-10-21] next_text / 会话有效性变了 → 刷新常驻通知文案（服务未运行则空操作）
                    EngineKeepAliveService.refresh(context)
                } else {
                    EngineKeepAliveService.stop(context)
                    EnginePatrol.cancelAll(context)
                    EngineHeartbeat.cancel(context)
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

    /** 安排测试闹钟；@return 是否使用了"精确/闹钟级"（false = 已降级为可能延迟的兼容模式） */
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, EngineAlarmReceiver::class.java)
        // [v14] 每次换新 requestCode：避免复用旧 PendingIntent 时，
        // 继承系统"闹钟调整"给上一次闹钟留下的推迟状态
        val prevRc = EnginePrefs.getTestAlarmRc(context)
        val rc = (prevRc + 1) % 1000
        EnginePrefs.setTestAlarmRc(context, rc)
        runCatching {
            PendingIntent.getBroadcast(
                context, prevRc, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { old ->
                am.cancel(old)
                old.cancel()
            }
        }
        val pi = PendingIntent.getBroadcast(
            context, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // [v13] 优先"闹钟级"（最不易被系统/厂商省电接管），失败再退精确/普通
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), pi)
            true
        } catch (t: Throwable) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                true
            } catch (t2: Throwable) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                false
            }
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
            .setSmallIcon(R.drawable.ic_stat_checkin)
            .setLargeIcon(EngineIcon.mainAppLarge(context))
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
        // [修复 2026-10-19] 今日已完成标记（主应用 → 引擎）
        const val EXTRA_CHECKED = "checked"
        // [review 2026-10-21] 会话是否仍然有效（false → 引擎暂停巡检）
        const val EXTRA_SESSION_VALID = "valid"
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
