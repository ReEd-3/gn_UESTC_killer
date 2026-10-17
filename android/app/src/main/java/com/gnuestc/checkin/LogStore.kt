package com.gnuestc.checkin

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简易控制台日志：环形保存最近 60 行。
 * 后台打卡、开机恢复、登录、服务开关等事件都写入这里，
 * 主界面按「运行日志」滚动展示，重开 App 也能看到历史。
 * [加密改造 2026-10-17] 日志可能包含姓名/坐标等隐私信息，整体经 SecretStore 加密存储。
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
            // [加密改造 2026-10-17] 原明文读写实现保留备查：
            // val lines = (sp.getString(KEY_LOG, "").orEmpty().split('\n') + entry)
            //     .filter { it.isNotBlank() }
            // val kept = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines
            // sp.edit().putString(KEY_LOG, kept.joinToString("\n")).apply()
            val lines = (readBlob(sp).split('\n') + entry).filter { it.isNotBlank() }
            val kept = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines
            writeBlob(sp, kept.joinToString("\n"))
        }
    }

    // [加密改造 2026-10-17] 原明文读取实现保留备查：
    // fun all(c: Context): List<String> =
    //     c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    //         .getString(KEY_LOG, "").orEmpty()
    //         .split('\n').filter { it.isNotBlank() }
    fun all(c: Context): List<String> =
        readBlob(c.getSharedPreferences(NAME, Context.MODE_PRIVATE))
            .split('\n').filter { it.isNotBlank() }

    fun clear(c: Context) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply()
    }

    /** 读取日志全量文本：自动识别密文，兼容旧版本明文（下次写入时迁移为密文） */
    private fun readBlob(sp: SharedPreferences): String {
        val raw = sp.getString(KEY_LOG, "").orEmpty()
        if (raw.isBlank()) return ""
        if (SecretStore.isEncrypted(raw)) return SecretStore.decrypt(raw).orEmpty()
        return raw
    }

    /** 写入日志全量文本（加密存储；加密不可用时降级明文） */
    private fun writeBlob(sp: SharedPreferences, text: String) {
        val stored = if (text.isBlank()) text else (SecretStore.encrypt(text) ?: text)
        sp.edit().putString(KEY_LOG, stored).apply()
    }
}
