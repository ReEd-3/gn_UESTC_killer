package com.gnuestc.checkin.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [保活 2026-10-22] 会话心跳触发入口：每次触发执行一次只读 /today，并自续下一次。
 * goAsync 让广播处理期间进程保持存活（心跳请求很短，通常 1 秒内完成）。
 */
class EngineHeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                EngineHeartbeat.beat(context)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
