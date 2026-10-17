package com.gnuestc.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 闹钟到点后触发：执行打卡并按结果安排下一次 */
class CheckinReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // [修复 2026-10-17] 记录触发日志：便于判断"到点是否真的跑了"
        LogStore.append(appContext, "[系统] 定时任务触发")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Prefs.isServiceEnabled(appContext)) {
                    LogStore.append(appContext, "[系统] 服务已关闭，已跳过本次执行")
                    return@launch
                }
                val result = CheckinRunner.run(appContext)
                Notifier.show(appContext, result)
                // [修复 2026-10-17] 先重排下一次闹钟，再做附加同步：即使进程随后被回收，
                // 打卡结果与下一次定时也不会丢
                CheckinRunner.rescheduleAfterRun(appContext, result)
                CheckinRunner.syncExtras(appContext)
                // [原实现保留备查]
                // if (CheckinRunner.isFinal(result)) {
                //     AlarmScheduler.scheduleDaily(appContext)
                // } else {
                //     AlarmScheduler.scheduleRetry(appContext)
                // }
            } catch (t: Throwable) {
                Notifier.show(appContext, "打卡异常：${t.message}")
                LogStore.append(appContext, "[打卡] 异常：${t.message}")
                AlarmScheduler.scheduleRetry(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
