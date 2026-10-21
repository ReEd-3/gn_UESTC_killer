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
                // [修复 2026-10-18] 窗口守卫：用户调整窗口后，旧的待触发闹钟可能落在窗口外，
                // 此时不做打卡尝试，按当前窗口重排即可（避免窗口外"不断尝试打卡"）
                if (!AlarmScheduler.isInWindowNow(appContext)) {
                    LogStore.append(appContext, "[系统] 当前不在打卡窗口内，跳过本次执行并重排")
                    AlarmScheduler.ensureScheduled(appContext)
                    return@launch
                }
                val result = CheckinRunner.run(appContext)
                Notifier.show(appContext, result)
                // [修复 2026-10-17] 先重排下一次闹钟，再做附加同步：即使进程随后被回收，
                // 打卡结果与下一次定时也不会丢
                CheckinRunner.rescheduleAfterRun(appContext, result)
                CheckinRunner.syncExtras(appContext)
                // [review 2026-10-21] 把最新状态（今日已完成 / 会话是否失效）同步给引擎：
                // 否则闹钟路径打完卡后引擎不知道，可能再跑一轮巡检；会话失效时也应让引擎暂停
                runCatching { EngineBridge.syncState(appContext) }
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
