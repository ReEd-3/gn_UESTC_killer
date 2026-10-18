package com.gnuestc.checkin.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [v7 巡检链] 巡检点触发：执行一轮巡检后由 EnginePatrol 续排下一环。
 * goAsync 让广播处理期间进程保持存活；引擎的常驻前台服务提供额外保护。
 */
class EnginePatrolReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
