package com.gnuestc.checkin

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
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
    // [深色开关已移回右上角胶囊，待审查后删除]
    // private var updatingDarkSwitch = false
    private var dotColor = 0
    private var logAnimator: ValueAnimator? = null
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainContent.setPadding(
                basePad + bars.left, basePad + bars.top, basePad + bars.right, basePad + bars.bottom
            )
            binding.drawerPanel.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // 深色模式开关（右上角胶囊）
        binding.btnTheme.setOnClickListener {
            val newDark = !Prefs.isDarkTheme(this)
            Prefs.setDarkTheme(this, newDark)
            LogStore.append(this, if (newDark) "[系统] 已切换为深色模式" else "[系统] 已切换为浅色模式")
            ThemeManager.setNight(newDark)
        }

        // 登录账号按钮（主界面重要入口）
        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // [其余旧按钮监听器已迁移至侧边栏，待审查后删除]
        /*
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
        */

        // 侧边栏（三条杠）与菜单交互
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(binding.drawerPanel) }

        // [深色开关已移回右上角胶囊，待审查后删除]
        /*
        binding.switchDark.setOnCheckedChangeListener { _, checked ->
            if (updatingDarkSwitch) return@setOnCheckedChangeListener
            if (checked != Prefs.isDarkTheme(this)) {
                Prefs.setDarkTheme(this, checked)
                LogStore.append(this, if (checked) "[系统] 已切换为深色模式" else "[系统] 已切换为浅色模式")
                ThemeManager.setNight(checked)
            }
        }
        binding.rowDark.setOnClickListener { binding.switchDark.toggle() }
        */

        binding.menuTest.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            startTestCheckin()
        }
        binding.menuLocation.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            showLocationMenu()
        }
        binding.menuWindow.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            showWindowMenu()
        }
        binding.menuPerms.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            openPermissionGuides()
        }
        // [登录账号已移回主界面按钮，待审查后删除]
        /*
        binding.menuLogin.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            startActivity(Intent(this, LoginActivity::class.java))
        }
        */

        // 未登录时点击「账号状态」卡片直接进入登录页
        binding.cardAccount.setOnClickListener {
            if (Prefs.getCookie(this).isBlank()) {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        // [脚本定位卡片已移入侧边栏，卡片入口停用，待审查后删除]
        // binding.cardLocation.setOnClickListener { showLocationMenu() }

        // 底部版本号与「关于」入口
        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.tvVersion.setOnClickListener { showAboutDialog() }
        binding.btnAbout.setOnClickListener { showAboutDialog() }

        binding.btnClearLog.setOnClickListener {
            LogStore.clear(this)
            LogStore.append(this, "[系统] 已清空日志")
            refreshConsole()
        }

        // [旧开关监听器已由圆形电源按钮取代，待审查后删除]
        /*
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
        */

        // 圆形电源按钮：点击切换后台打卡服务
        binding.powerToggle.setOnClickListener { toggleService() }

        // 日志区折叠/展开（状态记忆 + 抽屉式推拉动画）
        binding.logHeader.setOnClickListener {
            val collapsed = !Prefs.isLogCollapsed(this)
            Prefs.setLogCollapsed(this, collapsed)
            applyLogCollapsed(collapsed, animate = true)
        }
        applyLogCollapsed(Prefs.isLogCollapsed(this))

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

    /** 刷新所有状态展示 */
    private fun refreshStatus() {
        val logged = Prefs.getCookie(this).isNotBlank()
        val enabled = Prefs.isServiceEnabled(this)

        val (title, sub) = when {
            !enabled -> getString(R.string.service_off_title) to getString(R.string.service_off_sub)
            !logged -> getString(R.string.service_wait_title) to getString(R.string.service_wait_sub)
            else -> getString(R.string.service_on_title) to getString(R.string.service_on_sub)
        }
        binding.tvServiceTitle.text = title
        // [状态点已由圆形电源按钮取代，待审查后删除]
        // animateDot(dotRes)
        binding.tvServiceSub.text = sub
        binding.tvNext.text = getString(R.string.next_prefix) + AlarmScheduler.nextTriggerTimeText(this)

        // 账号状态卡片
        binding.tvAccount.text = getString(if (logged) R.string.logged_in else R.string.not_logged)

        // 登录按钮随登录态变化：未登录=主色实心引导；已登录=次要样式「重新登录」
        if (logged) {
            binding.btnLogin.setText(R.string.btn_relogin_text)
            binding.btnLogin.setBackgroundResource(R.drawable.bg_btn_secondary)
            binding.btnLogin.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            binding.btnLogin.setText(R.string.btn_login_text)
            binding.btnLogin.setBackgroundResource(R.drawable.bg_btn_primary)
            binding.btnLogin.setTextColor(ContextCompat.getColor(this, R.color.accent_text))
        }

        // 「最近打卡」按结果着色：成功绿 / 失败橙 / 其余默认
        val lastResult = Prefs.getLastResult(this)
        binding.tvLast.text = lastResult ?: getString(R.string.no_record)
        val lastColor = when {
            lastResult == null -> R.color.text_secondary
            lastResult.contains("成功") || lastResult.contains("已打卡") || lastResult.contains("已完成") -> R.color.dot_on
            lastResult.contains("失败") || lastResult.contains("异常") || lastResult.contains("未登录") -> R.color.dot_warn
            else -> R.color.text_primary
        }
        binding.tvLast.setTextColor(ContextCompat.getColor(this, lastColor))

        binding.btnTheme.text = getString(if (Prefs.isDarkTheme(this)) R.string.theme_dark else R.string.theme_light)
        binding.tvDrawerWindow.text = Prefs.windowLabel(this)
        // [脚本定位卡片已移入侧边栏，待审查后删除]
        // refreshLocationCard()

        // 圆形电源按钮同步服务状态（带颜色渐变动画）
        binding.powerToggle.isChecked = enabled

        refreshConsole()
    }

    /** 切换后台打卡服务（圆形电源按钮） */
    private fun toggleService() {
        val newState = !Prefs.isServiceEnabled(this)
        Prefs.setServiceEnabled(this, newState)
        if (newState) {
            AlarmScheduler.ensureScheduled(this)
            val hint = if (Prefs.getCookie(this).isBlank()) "（登录后自动生效）" else ""
            LogStore.append(this, "[系统] 后台打卡服务已启动$hint")
        } else {
            AlarmScheduler.cancel(this)
            LogStore.append(this, "[系统] 后台打卡服务已停止")
        }
        refreshStatus()
    }

    /** 应用日志折叠状态（animate=true 时抽屉式推拉动画，版本行保持固定底部） */
    private fun applyLogCollapsed(collapsed: Boolean, animate: Boolean = false) {
        binding.tvLogToggle.text = getString(
            if (collapsed) R.string.log_expand_arrow else R.string.log_collapse_arrow
        )
        binding.logHeader.contentDescription = getString(
            if (collapsed) R.string.log_expand else R.string.log_collapse
        )

        val card = binding.logCard
        val spacer = binding.spacerBottom
        val lp = card.layoutParams as LinearLayout.LayoutParams

        if (!animate) {
            // 初始状态直接应用（无动画）
            logAnimator?.cancel()
            logAnimator = null
            lp.weight = if (collapsed) 0f else 1f
            lp.height = 0
            card.layoutParams = lp
            card.visibility = if (collapsed) View.GONE else View.VISIBLE
            spacer.visibility = if (collapsed) View.VISIBLE else View.GONE
            return
        }

        val cardVisible = card.visibility == View.VISIBLE
        if (collapsed && !cardVisible) return

        // 推拉动画：仅改变日志卡高度，由底部占位 Space 吸收空间变化
        logAnimator?.cancel()
        spacer.visibility = View.VISIBLE
        lp.weight = 0f
        val startH: Int
        if (cardVisible) {
            startH = card.height
        } else {
            lp.height = 0
            card.layoutParams = lp
            card.visibility = View.VISIBLE
            startH = 0
        }
        val endH = if (collapsed) 0 else startH + spacer.height

        lp.height = startH
        card.layoutParams = lp

        var cancelled = false
        logAnimator = ValueAnimator.ofInt(startH, endH).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                card.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (collapsed) {
                        card.visibility = View.GONE
                        lp.height = 0
                        card.layoutParams = lp
                    } else {
                        lp.weight = 1f
                        lp.height = 0
                        card.layoutParams = lp
                        spacer.visibility = View.GONE
                    }
                }
            })
            start()
        }
    }

    // [状态点已由圆形电源按钮取代，该方法保留备查，待审查后删除]
    // /** 状态点颜色渐变动画，让状态变化一目了然 */
    // private fun animateDot(colorRes: Int) {
    //     val target = ContextCompat.getColor(this, colorRes)
    //     val from = if (dotColor == 0) target else dotColor
    //     ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
    //         duration = 300
    //         addUpdateListener { anim ->
    //             val c = anim.animatedValue as Int
    //             binding.dotStatus.background = GradientDrawable().apply {
    //                 shape = GradientDrawable.OVAL
    //                 setColor(c)
    //             }
    //         }
    //         start()
    //     }
    //     dotColor = target
    // }

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

    // [脚本定位卡片已移入侧边栏，卡片刷新逻辑停用，待审查后删除]
    // /** 刷新「脚本定位」卡片：坐标 + 距本账号打卡点距离 */
    // private fun refreshLocationCard() {
    //     val lat = Prefs.getLocationLat(this)
    //     val lng = Prefs.getLocationLng(this)
    //     val defaultMark = when {
    //         Prefs.isCustomLocation(this) -> ""
    //         Prefs.isDefaultPointKnown(this) ->
    //             getString(R.string.location_default_mark_named, Prefs.getDefaultName(this))
    //         else -> getString(R.string.location_default_mark)
    //     }
    //     binding.tvLocationCoords.text = String.format(Locale.US, "%.6f, %.6f", lat, lng) + defaultMark
    //
    //     val d = GeoUtil.distanceMeters(lat, lng, Prefs.getDefaultLat(this), Prefs.getDefaultLng(this))
    //     val inside = d <= Prefs.getDefaultRadius(this)
    //     binding.tvLocationDistance.text = getString(
    //         R.string.location_distance_fmt,
    //         formatDistance(d),
    //         getString(if (inside) R.string.location_in_fence else R.string.location_out_fence)
    //     )
    //     binding.tvLocationDistance.setTextColor(
    //         ContextCompat.getColor(this, if (inside) R.color.dot_on else R.color.dot_warn)
    //     )
    // }

    // [随脚本定位卡片一并停用，待审查后删除]
    /*
    private fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(Locale.US, "%.0f 米", meters)
        else String.format(Locale.US, "%.2f 公里", meters / 1000)
    */

    /** 测试打卡（侧边栏入口） */
    private fun startTestCheckin() {
        binding.progressBusy.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CheckinRunner.run(this@MainActivity) }
            binding.progressBusy.visibility = View.GONE
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    /** 调整时间窗口：查看当前窗口 / 立即同步 / 重置默认 */
    private fun showWindowMenu() {
        val source = getString(
            if (Prefs.isWindowSynced(this)) R.string.window_source_remote else R.string.window_source_default
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.window_title)
            .setMessage(getString(R.string.window_msg, Prefs.windowLabel(this), source))
            .setPositiveButton(R.string.window_sync_btn) { _, _ -> syncWindowNow() }
            .setNeutralButton(R.string.window_reset_btn) { _, _ ->
                Prefs.resetWindow(this)
                LogStore.append(this, getString(R.string.window_reset_done))
                AlarmScheduler.ensureScheduled(this)
                refreshStatus()
            }
            .setNegativeButton(R.string.window_close, null)
            .show()
    }

    /** 立即从服务端同步时间窗口（需要已登录） */
    private fun syncWindowNow() {
        val cookie = Prefs.getCookie(this)
        if (cookie.isBlank()) {
            Toast.makeText(this, R.string.not_logged, Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBusy.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                RemoteSync.refreshSchedule(this@MainActivity, CheckinApi(cookie))
            }
            binding.progressBusy.visibility = View.GONE
            val msg = if (ok) {
                getString(R.string.window_sync_ok, Prefs.windowLabel(this@MainActivity))
            } else {
                getString(R.string.window_sync_fail)
            }
            LogStore.append(this@MainActivity, "[窗口] $msg")
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            AlarmScheduler.ensureScheduled(this@MainActivity)
            refreshStatus()
        }
    }

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
