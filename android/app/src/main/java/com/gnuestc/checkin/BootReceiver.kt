package com.gnuestc.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后重建闹钟；如果开机时已过 21:05 但还在窗口内，尽快补一次 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        if (Prefs.getCookie(appContext).isBlank()) return

        // 统一走 ensureScheduled：未到点排今天、窗口内尽快补打、结束排明天
        AlarmScheduler.ensureScheduled(appContext)
        // [新增 2026-10-18] 开机后恢复常驻服务
        KeepAliveService.syncKeepAlive(appContext)
        if (Prefs.isServiceEnabled(appContext)) {
            LogStore.append(appContext, "[系统] 开机，已恢复定时任务")
        }
    }
}
