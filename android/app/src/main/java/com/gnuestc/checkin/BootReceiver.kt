package com.gnuestc.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** 开机后重建闹钟；如果开机时已过 21:05 但还在窗口内，尽快补一次 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // [修复 2026-10-19] 同时处理"应用更新"广播：主应用升级会清空已排闹钟，
        // 此前无人重建，升级当天可能直接漏打卡（引擎侧仅在引擎更新时才会重排）
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val isUpdated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUpdated) return
        val appContext = context.applicationContext
        // [修复 v12] 部分国产 ROM（如 ColorOS）会在应用被解冻/唤醒时"补发"开机广播，
        // 导致每次打开应用都记一条"开机"日志。真实开机时设备运行时长很短，以此过滤；
        // [修复 2026-10-19] 阈值 5→10 分钟（开机广播延迟的 ROM 也能覆盖）；更新广播不受此限制
        if (isBoot && SystemClock.elapsedRealtime() > 10 * 60_000L) return
        if (Prefs.getCookie(appContext).isBlank()) return

        // 统一走 ensureScheduled：未到点排今天、窗口内尽快补打、结束排明天
        AlarmScheduler.ensureScheduled(appContext)
        // [v7 巡检链] 开机/更新后把打卡参数同步给引擎，并唤醒引擎侧巡检链（窗口内每 5 分钟一环）
        EngineBridge.syncState(appContext)
        if (Prefs.isServiceEnabled(appContext)) {
            LogStore.append(
                appContext,
                if (isUpdated) "[系统] 应用已更新，已恢复定时任务" else "[系统] 开机，已恢复定时任务"
            )
        }
    }
}
