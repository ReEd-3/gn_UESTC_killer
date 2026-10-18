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
     * [v6/v10] 电池优化自检：未豁免时引导一次（重装组件后系统会重置该设置）。
     * ColorOS 说明：系统"一键允许"页只能改系统名单；OPPO 自己的冻结机制还看"应用耗电管理"，
     * 因此额外提供「打开耗电设置」直达该页；引导过一次后不再反复打扰（重装数据自然重置）。
     */
    private fun maybeCheckBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName) || EnginePrefs.isBatteryPromptDone(this)) {
            finish()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("后台组件：请允许后台运行")
            .setMessage(
                "为保证熄屏后定时打卡不被系统冻结：\n" +
                    "· 优先点「一键允许」，并在系统弹窗中点“允许”；\n" +
                    "· 若系统仍限制（如 ColorOS），请点「打开耗电设置」，选择“允许完全后台行为”。"
            )
            .setPositiveButton("一键允许") { _, _ ->
                EnginePrefs.setBatteryPromptDone(this, true)
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                finish()
            }
            .setNeutralButton("打开耗电设置") { _, _ ->
                EnginePrefs.setBatteryPromptDone(this, true)
                openVendorPowerDetail()
                finish()
            }
            .setNegativeButton("暂不") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** [v10] 打开厂商(OPPO/ColorOS)的「应用耗电管理」详情页；不可用时退回系统电池优化列表 */
    private fun openVendorPowerDetail() {
        val opened = runCatching {
            startActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.oplus.battery",
                        "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                    )
                    putExtra("pkg_name", packageName)
                }
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
