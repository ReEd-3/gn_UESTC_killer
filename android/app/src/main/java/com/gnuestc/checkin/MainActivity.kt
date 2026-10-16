package com.gnuestc.checkin

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gnuestc.checkin.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var updatingSwitch = false
    private var dotColor = 0
    private var pendingGpsRequest = false
    private val reqLocationCode = 1001

    /** 地图选点返回结果 */
    private val mapPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val lat = result.data?.getDoubleExtra(MapPickActivity.EXTRA_LAT, Double.NaN)
                val lng = result.data?.getDoubleExtra(MapPickActivity.EXTRA_LNG, Double.NaN)
                if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN()) {
                    Prefs.setLocation(this, lat, lng)
                    LogStore.append(this, "[定位] 地图选点已保存：%.6f, %.6f".format(Locale.US, lat, lng))
                    refreshStatus()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15/16 强制边到边显示：给根布局留出系统栏间距
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val basePad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(basePad + bars.left, basePad + bars.top, basePad + bars.right, basePad + bars.bottom)
            insets
        }

        binding.btnTheme.setOnClickListener {
            val newDark = !Prefs.isDarkTheme(this)
            Prefs.setDarkTheme(this, newDark)
            LogStore.append(this, if (newDark) "[系统] 已切换为深色模式" else "[系统] 已切换为浅色模式")
            ThemeManager.setNight(newDark)
        }

        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.btnTest.setOnClickListener {
            binding.btnTest.isEnabled = false
            binding.progressBusy.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { CheckinRunner.run(this@MainActivity) }
                binding.btnTest.isEnabled = true
                binding.progressBusy.visibility = View.GONE
                Toast.makeText(this@MainActivity, result, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }

        binding.btnPermissions.setOnClickListener { openPermissionGuides() }

        binding.cardLocation.setOnClickListener { showLocationMenu() }

        // 底部版本号与「关于」入口
        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.tvVersion.setOnClickListener { showAboutDialog() }
        binding.btnAbout.setOnClickListener { showAboutDialog() }

        binding.btnClearLog.setOnClickListener {
            LogStore.clear(this)
            LogStore.append(this, "[系统] 已清空日志")
            refreshConsole()
        }

        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            Prefs.setServiceEnabled(this, checked)
            if (checked) {
                AlarmScheduler.ensureScheduled(this)
                val hint = if (Prefs.getCookie(this).isBlank()) "（登录后自动生效）" else ""
                LogStore.append(this, "[系统] 后台打卡服务已启动$hint")
            } else {
                AlarmScheduler.cancel(this)
                LogStore.append(this, "[系统] 后台打卡服务已停止")
            }
            refreshStatus()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 每次打开 App 都确保定时已安排（被系统清理/强停后可自动恢复；窗口内打开会尽快补打）
        AlarmScheduler.ensureScheduled(this)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** 刷新所有状态展示（含状态点颜色渐变） */
    private fun refreshStatus() {
        val logged = Prefs.getCookie(this).isNotBlank()
        val enabled = Prefs.isServiceEnabled(this)

        val (title, dotRes, sub) = when {
            !enabled -> Triple(getString(R.string.service_off_title), R.color.dot_off, getString(R.string.service_off_sub))
            !logged -> Triple(getString(R.string.service_wait_title), R.color.dot_warn, getString(R.string.service_wait_sub))
            else -> Triple(getString(R.string.service_on_title), R.color.dot_on, getString(R.string.service_on_sub))
        }
        binding.tvServiceTitle.text = title
        animateDot(dotRes)
        binding.tvServiceSub.text = sub
        binding.tvNext.text = getString(R.string.next_prefix) + AlarmScheduler.nextTriggerTimeText(this)

        binding.tvAccount.text = getString(if (logged) R.string.logged_in else R.string.not_logged)
        binding.tvLast.text = Prefs.getLastResult(this) ?: getString(R.string.no_record)
        binding.btnTheme.text = getString(if (Prefs.isDarkTheme(this)) R.string.theme_dark else R.string.theme_light)
        refreshLocationCard()

        updatingSwitch = true
        binding.switchService.isChecked = enabled
        updatingSwitch = false

        refreshConsole()
    }

    /** 状态点颜色渐变动画，让状态变化一目了然 */
    private fun animateDot(colorRes: Int) {
        val target = ContextCompat.getColor(this, colorRes)
        val from = if (dotColor == 0) target else dotColor
        ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 300
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                binding.dotStatus.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
            }
            start()
        }
        dotColor = target
    }

    /** 把日志渲染到日志卡片并滚到底部 */
    private fun refreshConsole() {
        val lines = LogStore.all(this)
        binding.tvConsole.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
        binding.scrollConsole.post { binding.scrollConsole.fullScroll(View.FOCUS_DOWN) }
    }

    /** 应用介绍（文本位于 strings.xml 的 about_content） */
    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_content)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    /** 刷新「脚本定位」卡片：坐标 + 距本账号打卡点距离 */
    private fun refreshLocationCard() {
        val lat = Prefs.getLocationLat(this)
        val lng = Prefs.getLocationLng(this)
        val defaultMark = when {
            Prefs.isCustomLocation(this) -> ""
            Prefs.isDefaultPointKnown(this) ->
                getString(R.string.location_default_mark_named, Prefs.getDefaultName(this))
            else -> getString(R.string.location_default_mark)
        }
        binding.tvLocationCoords.text = String.format(Locale.US, "%.6f, %.6f", lat, lng) + defaultMark

        val d = GeoUtil.distanceMeters(lat, lng, Prefs.getDefaultLat(this), Prefs.getDefaultLng(this))
        val inside = d <= Prefs.getDefaultRadius(this)
        binding.tvLocationDistance.text = getString(
            R.string.location_distance_fmt,
            formatDistance(d),
            getString(if (inside) R.string.location_in_fence else R.string.location_out_fence)
        )
        binding.tvLocationDistance.setTextColor(
            ContextCompat.getColor(this, if (inside) R.color.dot_on else R.color.dot_warn)
        )
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(Locale.US, "%.0f 米", meters)
        else String.format(Locale.US, "%.2f 公里", meters / 1000)

    /** 脚本定位设置菜单 */
    private fun showLocationMenu() {
        val items = arrayOf(
            getString(R.string.location_actions_map),
            getString(R.string.location_actions_gps),
            getString(R.string.location_actions_manual),
            getString(R.string.location_actions_reset)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_dialog_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> mapPicker.launch(Intent(this, MapPickActivity::class.java))
                    1 -> startUseCurrentLocation()
                    2 -> showManualInput()
                    3 -> {
                        Prefs.resetLocation(this)
                        LogStore.append(this, "[定位] 已重置为默认定位点（${Prefs.getDefaultName(this)}）")
                        refreshStatus()
                    }
                }
            }
            .show()
    }

    /** 手动输入 GCJ02 坐标 */
    private fun showManualInput() {
        val view = layoutInflater.inflate(R.layout.dialog_location_input, null)
        val editLat = view.findViewById<EditText>(R.id.editLat)
        val editLng = view.findViewById<EditText>(R.id.editLng)
        editLat.setText(String.format(Locale.US, "%.6f", Prefs.getLocationLat(this)))
        editLng.setText(String.format(Locale.US, "%.6f", Prefs.getLocationLng(this)))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_actions_manual)
            .setView(view)
            .setPositiveButton(R.string.manual_save) { _, _ ->
                val lat = editLat.text.toString().trim().toDoubleOrNull()
                val lng = editLng.text.toString().trim().toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    Toast.makeText(this, R.string.manual_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setLocation(this, lat, lng)
                    LogStore.append(this, "[定位] 手动输入已保存：%.6f, %.6f".format(Locale.US, lat, lng))
                    refreshStatus()
                }
            }
            .setNegativeButton(R.string.map_cancel, null)
            .show()
    }

    /** 使用当前位置（手机定位为 WGS84，保存前自动换算为 GCJ02） */
    private fun startUseCurrentLocation() {
        if (Locator.hasPermission(this)) {
            doLocateNow()
        } else {
            pendingGpsRequest = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                reqLocationCode
            )
        }
    }

    private fun doLocateNow() {
        binding.progressBusy.visibility = View.VISIBLE
        LogStore.append(this, "[定位] 正在获取当前位置…")
        Locator.getBestOnce(this) { loc ->
            binding.progressBusy.visibility = View.GONE
            if (loc == null) {
                LogStore.append(this, "[定位] 获取当前位置失败")
                Toast.makeText(this, R.string.location_gps_failed, Toast.LENGTH_SHORT).show()
            } else {
                val (gcjLat, gcjLng) = GeoUtil.wgs84ToGcj02(loc.latitude, loc.longitude)
                Prefs.setLocation(this, gcjLat, gcjLng)
                LogStore.append(this, "[定位] 已使用当前位置（WGS84 已自动换算 GCJ02）")
                refreshStatus()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqLocationCode && pendingGpsRequest) {
            pendingGpsRequest = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doLocateNow()
            } else {
                LogStore.append(this, "[定位] 未授予定位权限")
                Toast.makeText(this, R.string.location_perm_needed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPermissionGuides() {
        // 1) 精确闹钟权限（Android 12/13 需要；14+ 已在清单声明 USE_EXACT_ALARM 默认授予）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            LogStore.append(this, "[权限] 需要授予「精确闹钟」权限")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }
        // 2) 电池优化白名单（国产 ROM 还需在系统设置里允许自启动/后台运行）
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            LogStore.append(this, "[权限] 建议加入电池优化白名单")
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            return
        }
        LogStore.append(this, "[权限] 已就绪")
        Toast.makeText(this, "权限已就绪", Toast.LENGTH_SHORT).show()
    }
}
