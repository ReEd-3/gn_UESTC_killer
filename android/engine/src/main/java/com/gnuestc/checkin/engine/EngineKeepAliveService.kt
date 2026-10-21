package com.gnuestc.checkin.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [双包 2026-10-18] 引擎自己的常驻前台服务：把"打卡引擎"亮在系统面前——
 * 低优先级常驻通知 + 前台服务优先级，降低引擎被系统静默回收的概率。
 *
 * 为什么放在引擎侧：主应用会被"划卡片"强停（常驻也没用），而引擎不在最近任务
 * 出现（划不到），此常驻通知即引擎存活状态的唯一可视入口。
 * 与主应用的「后台自动打卡服务」开关联动（主应用经 sync 指令同步）。
 */
class EngineKeepAliveService : Service() {

    /** [v5] 常驻备份定时器用的主线程 Handler（进程存活期间有效） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [v9] 测试闹钟"存活心跳"任务（每秒刷新一次"已安排"通知） */
    private var tickerRunnable: Runnable? = null

    /** [v9] 开始存活心跳：秒数在跳=引擎活着；被系统冻结=秒数停跳（一眼可辨） */
    private fun startTickerInternal(triggerAt: Long, detail: String) {
        stopTickerInternal()
        val r = object : Runnable {
            override fun run() {
                val nowMs = System.currentTimeMillis()
                val left = triggerAt - nowMs
                // [v13] 到点后继续跳动（改为"等待系统投递"），直到真触发或超时 1 小时——
                // 这样"闹钟被系统推迟"与"引擎冻结"可区分：前者心跳继续、后者心跳停止
                if (left <= 0 && nowMs - triggerAt > 60 * 60_000L) {
                    stopTickerInternal()
                    return
                }
                runCatching { updateScheduledNotification(triggerAt, detail, left) }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        tickerRunnable = r
        mainHandler.post(r)
    }

    private fun stopTickerInternal() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    /** [v9] 刷新"测试闹钟已安排"通知（与 EngineReceiver 同 ID/同频道，叠加跳动秒数） */
    private fun updateScheduledNotification(triggerAt: Long, detail: String, leftMs: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("engine_status", "引擎状态", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val heartbeat = fmt.format(Date())
        val text = if (leftMs > 0) {
            "将于 ${fmt.format(Date(triggerAt))} 触发 · $detail · 剩 ${leftMs / 1000} 秒 · 心跳 $heartbeat"
        } else {
            "已到点，等待系统投递（若长时间未触发=被系统延迟）· 心跳 $heartbeat"
        }
        val n = NotificationCompat.Builder(this, "engine_status")
            .setSmallIcon(R.drawable.ic_stat_checkin)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(
                if (leftMs > 0) "打卡引擎 · 测试闹钟已安排（心跳跳动中）"
                else "打卡引擎 · 测试闹钟已到点（等待触发）"
            )
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        nm.notify(EngineReceiver.NOTIFY_SCHEDULED, n)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        createChannel(this)
        EngineEvents.send(this, EngineEvents.EVENT_SERVICE_CREATED)
        // [v9] 若在服务启动前就有测试闹钟等待心跳，补挂
        val pt = pendingTickerAt
        if (pt > System.currentTimeMillis()) startTickerInternal(pt, pendingTickerDetail)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        // [Android 14+] 前台服务需声明类型：本服务用 specialUse（守护定时任务）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // [v7 巡检链 / v8 网格] 服务启动/被拉起时确保巡检链存在（重装/重启/更新后自动恢复）
        EnginePatrol.scheduleAll(this)
        return START_STICKY
    }

    override fun onDestroy() {
        EngineEvents.send(this, EngineEvents.EVENT_SERVICE_DESTROYED)
        running = false
        instance = null
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

        /** [v5] 运行中的服务实例（供"常驻备份"定时器使用） */
        @Volatile
        private var instance: EngineKeepAliveService? = null

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

        /**
         * [v5] 常驻备份通道：在常驻服务进程内挂一条 Handler 延迟消息。
         * 主应用被划掉后常驻服务仍存活（已实测），只要进程活着，这条通道到点即触发，
         * 作为"系统闹钟被系统清理/延后"时的兜底。
         * @return 是否成功挂上（服务未运行时返回 false，系统闹钟通道仍有效）
         */
        fun scheduleTestBackup(context: Context, triggerAtMillis: Long): Boolean {
            val svc = instance ?: return false
            val delay = triggerAtMillis - System.currentTimeMillis()
            if (delay <= 0) return false
            svc.mainHandler.postDelayed({
                EngineEvents.send(svc.applicationContext, EngineEvents.EVENT_BACKUP_FIRED, "常驻备份")
                onTestAlarmFired(svc.applicationContext) // [v9] 备份通道触发：停心跳 + 撤下"已安排"通知
                EngineAlarmReceiver.postFired(
                    svc.applicationContext,
                    EngineAlarmReceiver.NOTIFY_ID_BACKUP,
                    "常驻备份"
                )
            }, delay)
            return true
        }

        /** [v9] 待启动的心跳（服务未就绪时先记账，onCreate 时补挂） */
        @Volatile
        private var pendingTickerAt = 0L

        @Volatile
        private var pendingTickerDetail = ""

        /** [v9] 开始"存活心跳"：每秒刷新"已安排"通知（被系统冻结则秒数停跳，一眼可辨） */
        fun startTestTicker(context: Context, triggerAtMillis: Long, detail: String) {
            pendingTickerAt = triggerAtMillis
            pendingTickerDetail = detail
            instance?.startTickerInternal(triggerAtMillis, detail)
        }

        /** [v9] 测试闹钟已触发：停心跳 + 撤下"已安排"通知 */
        fun onTestAlarmFired(context: Context) {
            pendingTickerAt = 0L
            instance?.stopTickerInternal()
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(EngineReceiver.NOTIFY_SCHEDULED)
            }
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
            // [文案 2026-10-19] 正式版定稿：去掉"引擎/守护"等内部用语
            val text = if (next.isBlank()) {
                "点击打开应用"
            } else {
                "下次打卡：$next（点击打开应用）"
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_checkin)
                .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
                .setContentTitle("自动打卡运行中")
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
