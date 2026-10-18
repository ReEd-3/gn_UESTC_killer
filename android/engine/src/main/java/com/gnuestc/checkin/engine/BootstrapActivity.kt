package com.gnuestc.checkin.engine

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * [双包 2026-10-18] 无界面引导页：安装后由主应用拉起一次，用于
 *  1) 解除"新安装应用"的 stopped 状态（保证闹钟与广播后续都能正常送达）
 *  2) 申请通知权限（引擎自身没有界面，借用此半透明页完成系统弹窗）
 *  3) [新增] 检查「精确闹钟」权限：没有它定时可能被系统延后，缺失则弹说明并可一键跳转
 * 完成后立即结束，用户几乎无感。
 */
class BootstrapActivity : Activity() {

    private var askedAlarmPermission = false
    private var askedBatteryOptimization = false

    companion object {
        /** 与主应用 EngineBridge 约定：启动意图携带的"是否开启常驻"开关 */
        private const val EXTRA_KEEPALIVE = "keepalive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [修复 2026-10-18] 按主应用随启动意图携带的开关状态启停常驻服务，
        // 避免"引导页先于 sync 指令到达"时漏启动常驻；未携带时退回读取本地已同步的开关。
        val wantKeepAlive = intent?.getBooleanExtra(EXTRA_KEEPALIVE, EnginePrefs.isKeepAliveEnabled(this))
            ?: EnginePrefs.isKeepAliveEnabled(this)
        EnginePrefs.setKeepAliveEnabled(this, wantKeepAlive)
        if (wantKeepAlive) EngineKeepAliveService.start(this) else EngineKeepAliveService.stop(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            maybeCheckExactAlarm()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        maybeCheckExactAlarm()
    }

    // [修复 v9] 原 onResume 兜底会在"首次 onResume"就把刚弹出的对话框秒关
    // （onCreate 同步链里已设置标志位，紧接着的第一次 onResume 立即 finish）；
    // 且 noHistory=true 已能保证"跳系统设置页后返回时活动自动结束"，此兜底已无必要：
    // override fun onResume() {
    //     super.onResume()
    //     // 从系统设置页（精确闹钟 / 电池优化）返回后结束
    //     if (askedAlarmPermission || askedBatteryOptimization) finish()
    // }

    /** 精确闹钟权限自检（缺失时给出说明入口） */
    private fun maybeCheckExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                askedAlarmPermission = true
                android.app.AlertDialog.Builder(this)
                    .setTitle("后台组件：需要「闹钟和提醒」权限")
                    .setMessage("为保证定时打卡准时触发，请允许本组件使用闹钟和提醒。")
                    .setPositiveButton("去允许") { _, _ ->
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                        finish()
                    }
                    .setNegativeButton("暂不") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
                return
            }
        }
        maybeCheckBattery()
    }

    /**
     * [v6/v11] 电池优化自检（厂商无关）：未豁免时按"标准意图阶梯"分级引导。
     * 不假设任何厂商：每一级都用系统标准意图（由系统自行解析/路由到它自家的页面）；
     * 每次激活先用标准 API 复检是否真的生效，未生效则进入下一级（最多 3 级，不会无限骚扰）。
     */
    private fun maybeCheckBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName) || EnginePrefs.isBatteryPromptDone(this)) {
            finish()
            return
        }
        val stage = EnginePrefs.getBatteryPromptStage(this).coerceIn(0, 2)
        val message = when (stage) {
            0 -> "为保证熄屏后定时打卡不被系统冻结，请点「去允许」，并在接下来的系统界面中允许本应用。"
            1 -> "刚才的允许似乎未生效。请点「去设置」，在打开的页面中允许本应用（不同系统叫法不同：不优化 / 允许后台运行 / 允许完全后台行为）。"
            else -> "最后一招：请点「应用信息」，在该页面中找到本应用的「电池 / 耗电管理 / 后台运行」相关选项并允许。"
        }
        val actionText = when (stage) {
            0 -> "去允许"
            1 -> "去设置"
            else -> "应用信息"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("后台组件：请允许后台运行")
            .setMessage(message)
            .setPositiveButton(actionText) { _, _ ->
                val launched = launchBatteryAssist(stage)
                if (!launched) {
                    android.widget.Toast.makeText(
                        this,
                        "未能打开系统设置，请手动在系统设置中允许本应用后台运行",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                EnginePrefs.setBatteryPromptStage(this, (stage + 1).coerceAtMost(2))
                if (stage >= 2) EnginePrefs.setBatteryPromptDone(this, true)
                finish()
            }
            .setNegativeButton("暂不") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * [v11] 依次尝试系统标准入口（不含任何厂商名称），成功打开一个即返回：
     * 标准意图由系统自行解析路由（例如某些系统会把它映射到自家的省电/耗电管理页面）。
     */
    private fun launchBatteryAssist(stage: Int): Boolean {
        val pkg = Uri.parse("package:$packageName")
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        val settingsList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val ordered = when (stage) {
            0 -> listOf(request, settingsList, details)
            1 -> listOf(settingsList, request, details)
            else -> listOf(details, settingsList)
        }
        for (intent in ordered) {
            if (runCatching { startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
