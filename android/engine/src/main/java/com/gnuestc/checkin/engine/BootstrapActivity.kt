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
    // [v15] 已跳系统页面，等待用户返回后复检（"点了允许就没下文"的修复核心）
    private var awaitingReturn = false

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

    /**
     * [v15] 从系统页面/弹窗返回后复检：
     *  - 已生效 → 提示"已生效 ✓"并结束；
     *  - 未生效 → 立即升级到下一级引导（不再"点完就没了"）。
     * 首次 onResume（刚从 onCreate 进入）不受影响（awaitingReturn=false）。
     */
    override fun onResume() {
        super.onResume()
        if (!awaitingReturn) return
        awaitingReturn = false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            EnginePrefs.setBatteryPromptDone(this, true)
            android.widget.Toast.makeText(this, "后台运行权限已生效 ✓", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 未生效 → 当场升级引导
        val next = (EnginePrefs.getBatteryPromptStage(this) + 1).coerceAtMost(2)
        EnginePrefs.setBatteryPromptStage(this, next)
        if (next >= 2) EnginePrefs.setBatteryPromptDone(this, true)
        showBatteryDialog(next)
    }

    /** [v15] 用户未返回而是离开了引导页 → 结束，下次激活再来（不残留） */
    override fun onStop() {
        super.onStop()
        if (!awaitingReturn) finish()
    }

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
     * [v6/v15] 电池优化自检（厂商无关，闭环引导）：
     * 未豁免时分级引导；每级跳转后由 onResume 复检，未生效当场升级，已生效当场确认。
     */
    private fun maybeCheckBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName) || EnginePrefs.isBatteryPromptDone(this)) {
            finish()
            return
        }
        showBatteryDialog(EnginePrefs.getBatteryPromptStage(this))
    }

    /** [v15] 按阶梯等级弹出引导对话框 */
    private fun showBatteryDialog(stage: Int) {
        val st = stage.coerceIn(0, 2)
        val message = when (st) {
            0 -> "为保证熄屏/划掉后台后定时打卡仍能运行，请允许本组件在后台运行。\n" +
                "（部分系统在下一步弹窗里点\"允许\"并不足够，返回后会自动检查并继续引导）"
            1 -> "刚才的允许没有真正生效。请点「打开应用信息」，在该页面的「电池 / 耗电管理」中选择" +
                "\"允许完全后台行为\"（不同系统叫法不同：不优化 / 允许后台运行）。"
            else -> "最后一步：打开应用信息后，找到本应用的「后台运行 / 耗电管理」相关开关并允许。"
        }
        val action = if (st == 0) "去允许" else "打开应用信息"
        android.app.AlertDialog.Builder(this)
            .setTitle("后台组件：需要后台运行权限")
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onBatteryAction(st) }
            .setNegativeButton("暂不") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** [v15] 执行跳转；成功跳转后保留页面，等用户返回复检 */
    private fun onBatteryAction(stage: Int) {
        val launched = if (stage == 0) launchRequestIgnore() else launchPowerSettings()
        if (!launched) {
            android.widget.Toast.makeText(
                this,
                "未能打开系统页面，请手动在系统设置中允许本应用后台运行",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        awaitingReturn = true
    }

    /** stage0：系统标准"忽略电池优化"请求（由系统自行决定弹窗或页面） */
    private fun launchRequestIgnore(): Boolean =
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.isSuccess

    /**
     * [v15] stage1+：依次尝试「厂商耗电管理页 → 应用信息页（标准）→ 电池优化列表（标准）」，
     * 打开一个即成功。厂商页为"尽力而为"（全品牌通用表，打不开自动落到标准页面）。
     */
    private fun launchPowerSettings(): Boolean {
        val vendors = listOf(
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
        )
        for ((p, c) in vendors) {
            val ok = runCatching {
                startActivity(
                    Intent().apply {
                        component = ComponentName(p, c)
                        putExtra("pkg_name", packageName)
                        putExtra("package_name", packageName)
                        putExtra("packageLabel", packageName)
                        putExtra("show_mode", 1)
                    }
                )
            }.isSuccess
            if (ok) return true
        }
        // 标准兜底：应用信息页（全平台都有）→ 电池优化列表
        if (runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }.isSuccess
        ) return true
        return runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess
    }
}
