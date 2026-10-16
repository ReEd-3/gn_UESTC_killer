package com.gnuestc.checkin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简易控制台日志：环形保存最近 60 行。
 * 后台打卡、开机恢复、登录、服务开关等事件都写入这里，
 * 主界面按终端风格滚动展示，重开 App 也能看到历史。
 */
object LogStore {
    private const val NAME = "gn_checkin"
    private const val KEY_LOG = "console_log"
    private const val MAX_LINES = 60

    fun append(c: Context, line: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$ts] $line"
            val sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            val lines = (sp.getString(KEY_LOG, "").orEmpty().split('\n') + entry)
                .filter { it.isNotBlank() }
            val kept = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines
            sp.edit().putString(KEY_LOG, kept.joinToString("\n")).apply()
        }
    }

    fun all(c: Context): List<String> =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun clear(c: Context) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply()
    }
}
