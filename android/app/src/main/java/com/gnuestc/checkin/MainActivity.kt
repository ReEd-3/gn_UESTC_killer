package com.gnuestc.checkin

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
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
    // [修复 2026-10-17] 打开应用时「前台自动补打」的运行标记与节流时间
    private var catchUpRunning = false
    private var lastCatchUpAt = 0L
    // [修复 2026-10-19] 打开应用时的服务端状态探测（节流 + 会话失效只提示一次）
    private var lastProbeAt = 0L
    private var sessionInvalidPrompted = false
    // [修复 2026-10-19] 上次同步给引擎的状态签名：只有状态真的变化才发 sync
    //（避免频繁 onResume 让引擎反复全量重排闹钟）
    private var lastEngineSyncSig: String? = null
    private var pendingGpsRequest = false
    private val reqLocationCode = 1001
    // [双包 2026-10-18] 安装流程续接标记：先去授权"安装未知应用"，返回后自动继续安装
    private var pendingUnknownSources = false
    // [修复 2026-10-18] 已发起引擎安装、待回前台校验结果（防止"安装没生效却无提示"）
    private var awaitingEngineInstall = false
    // [修复 2026-10-18] "卸载后重装"流程标记：卸载完成后自动重装内嵌引擎（修复签名冲突等场景）
    private var pendingEngineReinstall = false
    private var reinstallRequestedAt = 0L
    // [修复 v12] 本次会话是否已做过"活动唤醒"引擎（每次打开应用做一次，用于恢复被冻结/停掉的引擎）
    private var engineActivatedThisSession = false

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
                    // [修复 2026-10-19] 坐标变化立即同步引擎（引擎巡检用新坐标提交）
                    syncEngineStateNow()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // [修复 2026-10-19] 主题已改由 App(Application) 统一应用，避免 Resources 配置竞态；保留备查
        // ThemeManager.apply(this)
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
        // [双包 2026-10-18] 后台组件（引擎）入口
        binding.menuEngine.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawerPanel)
            showEngineMenu()
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

        // [首次引导 2026-10-17] 首次打开先弹「使用须知」，通知权限请求延后到引导确认后，
        // 避免系统权限弹窗与引导弹窗叠在一起
        if (!Prefs.isFirstRunGuideShown(this)) {
            showFirstRunGuide()
        } else {
            requestNotificationPermissionIfNeeded()
        }

        // 每次打开 App 都确保定时已安排（被系统清理/强停后可自动恢复；窗口内打开会尽快补打）
        AlarmScheduler.ensureScheduled(this)
        refreshStatus()
        // [双包 2026-10-18] 检测后台组件安装状态，必要时引导安装
        maybePromptEngineInstall()
        // [双包 2026-10-18] 把服务开关与下次打卡时间同步给引擎（常驻定义在引擎侧）
        // [修复 2026-10-19] 改为“同步并记录签名”：首次启动必发一次，随后 onResume 不会重复发送
        syncEngineStateNow()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // [修复 2026-10-17] 窗口内打开应用 → 立即前台补打（替代此前"排 1 分钟后等闹钟"的做法）
        maybeRunForegroundCatchUp()
        // [修复 2026-10-19] 本地"今日已完成"标记与服务器对账 → 升级为双向的"服务端状态探测"：
        // 顺带提前发现会话失效（别处登录/过期），不再等窗口内打卡尝试时才发现
        // [旧入口保留备查]
        // reconcileCheckedState()
        probeServerState()
        // [修复 2026-10-19] 回到前台按“状态签名”决定是否同步引擎：
        // 状态（下次触发/已完成/开关/窗口/坐标）未变化就不发广播，避免每次 onResume 让引擎全量重排
        syncEngineStateIfChanged()
        // [双包 2026-10-18] 安装流程续接：授权返回后自动继续安装、安装完自动激活
        resumeEngineInstallFlow()
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
        // [文案 2026-10-19] 卡片展示用「用户向」文案；下面的着色判断仍用原始结果
        binding.tvLast.text = lastResult?.let { CheckinRunner.userText(it) } ?: getString(R.string.no_record)
        val lastColor = when {
            lastResult == null -> R.color.text_secondary
            lastResult.contains("成功") || lastResult.contains("已打卡") || lastResult.contains("已完成") -> R.color.dot_on
            lastResult.contains("失败") || lastResult.contains("异常") || lastResult.contains("未登录") -> R.color.dot_warn
            else -> R.color.text_primary
        }
        binding.tvLast.setTextColor(ContextCompat.getColor(this, lastColor))

        binding.btnTheme.text = getString(if (Prefs.isDarkTheme(this)) R.string.theme_dark else R.string.theme_light)
        // [手动设置 2026-10-18] 抽屉里同时显示窗口来源（手动 / 服务端 / 默认）
        // 原实现：binding.tvDrawerWindow.text = Prefs.windowLabel(this)
        val winSource = when {
            Prefs.isWindowManual(this) -> getString(R.string.window_source_manual)
            Prefs.isWindowSynced(this) -> getString(R.string.window_source_remote)
            else -> getString(R.string.window_source_default)
        }
        binding.tvDrawerWindow.text = Prefs.windowLabel(this) + " · " + winSource
        // [脚本定位卡片已移入侧边栏，待审查后删除]
        // refreshLocationCard()

        // 圆形电源按钮同步服务状态（带颜色渐变动画）
        binding.powerToggle.isChecked = enabled

        refreshConsole()
    }

    /** [双包 2026-10-18] 切换后台打卡服务（圆形电源按钮）：本地开关 + 同步给引擎 */
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
        // [双包 2026-10-18] 把开关状态同步给引擎（引擎侧负责常驻通知）
        syncEngineStateNow()
        refreshStatus()
    }

    /** [双包 2026-10-18] 同步服务开关与会话/坐标/窗口给引擎（[v7] 统一走 EngineBridge.syncState） */
    private fun syncEngineState() {
        EngineBridge.syncState(this)
    }

    /** [修复 2026-10-19] 引擎同步签名：任一状态变化都需要重新同步 */
    private fun engineSyncSig(): String = buildString {
        append(Prefs.getNextTrigger(this@MainActivity)).append('|')
        append(Prefs.isCheckedToday(this@MainActivity)).append('|')
        append(Prefs.isServiceEnabled(this@MainActivity)).append('|')
        append(Prefs.getWindowStartMinute(this@MainActivity)).append('|')
        append(Prefs.getWindowEndMinute(this@MainActivity)).append('|')
        append(Prefs.getLocationLat(this@MainActivity)).append('|')
        append(Prefs.getLocationLng(this@MainActivity))
    }

    /** [修复 2026-10-19] 仅在状态签名变化时同步（onResume 等高频路径使用） */
    private fun syncEngineStateIfChanged() {
        val sig = engineSyncSig()
        if (sig == lastEngineSyncSig) return
        lastEngineSyncSig = sig
        syncEngineState()
    }

    /** [修复 2026-10-19] 主动同步并记录签名（打卡完成/改窗口/改坐标等明确变更后使用） */
    private fun syncEngineStateNow() {
        lastEngineSyncSig = engineSyncSig()
        syncEngineState()
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
            duration = 380
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
        // [修复 2026-10-18] 冷启动首次刷新常发生在布局完成之前，单次 post 会落空（日志停在最旧处）：
        // 布局完成后滚底 + 补一次延迟兜底，保证打开应用时看到的是最新日志
        binding.scrollConsole.post { binding.scrollConsole.fullScroll(View.FOCUS_DOWN) }
        binding.scrollConsole.postDelayed({ binding.scrollConsole.fullScroll(View.FOCUS_DOWN) }, 120L)
    }

    /** 应用介绍（文本位于 strings.xml 的 about_content；网址可轻点打开、文本可长按选中复制） */
    private fun showAboutDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_content)
            .setPositiveButton(R.string.about_ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.let { message ->
                // 1) 网址自动识别为可点击链接；2) 开启长按选择；3) 挂载兼容「链接点击 + 文本选择」的移动方法
                Linkify.addLinks(message, Linkify.WEB_URLS)
                message.setTextIsSelectable(true)
                message.movementMethod = SelectableLinkMovementMethod()
            }
        }
        dialog.show()
    }

    /** 首次打开的使用须知引导（确认后记录标记，并补发通知权限请求） */
    private fun showFirstRunGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_title)
            .setMessage(R.string.first_run_content)
            .setCancelable(false)
            .setPositiveButton(R.string.first_run_ok) { _, _ ->
                Prefs.setFirstRunGuideShown(this, true)
                requestNotificationPermissionIfNeeded()
            }
            .show()
    }

    /** Android 13+ 请求通知权限（首次引导确认后或后续启动时调用） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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
            withContext(Dispatchers.IO) {
                CheckinRunner.rescheduleAfterRun(this@MainActivity, result)
                CheckinRunner.syncExtras(this@MainActivity)
                // [修复 2026-10-19] 打完立即同步引擎（同上）
                syncEngineStateNow()
            }
            binding.progressBusy.visibility = View.GONE
            Toast.makeText(this@MainActivity, CheckinRunner.userText(result), Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    /**
     * [修复 2026-10-18] 本地标记对账：本地认为"今天已完成"，但服务器 /today 明确显示未打卡时，
     * 说明此前的"成功"是误报（如服务端回执异常却未真正记录），清除标记并恢复打卡任务。
     * 查询失败（网络问题）时保守不动，避免误清。
     * [修复 2026-10-19] 已被 [probeServerState] 取代（增加"服务端已打卡→补记本地"与"会话失效提示"，
     * 并修正了会话失效时被误判为"未打卡"的问题）；保留备查。
     */
    private fun reconcileCheckedState() {
        if (!Prefs.isCheckedToday(this)) return
        val cookie = Prefs.getCookie(this)
        if (cookie.isBlank()) return
        lifecycleScope.launch {
            val actuallyChecked = withContext(Dispatchers.IO) {
                // [修复 2026-10-19] 改用 parseTodayChecked：会话失效/响应异常时返回 null（不做判定），
                // 此前会被误判为"未打卡"而清除本正确的标记
                runCatching { parseTodayChecked(CheckinApi(cookie).getToday()) }.getOrNull()
            }
            // 只有明确查询成功且为"未打卡"才清除（null = 查询失败/响应异常，保持不动）
            if (actuallyChecked == false) {
                Prefs.clearCheckedToday(this@MainActivity)
                Prefs.setLastResult(this@MainActivity, "此前打卡未生效，已恢复自动打卡")
                LogStore.append(
                    this@MainActivity,
                    "[系统] 检测到此前打卡未生效（服务端无记录），已清除标记并恢复任务"
                )
                AlarmScheduler.ensureScheduled(this@MainActivity)
                refreshStatus()
                maybeRunForegroundCatchUp()
            }
        }
    }

    /**
     * [修复 2026-10-19] 解析 /today 响应：
     *  - 返回 true/false = 服务端明确给出是否已打卡；
     *  - 返回 null = 无法判定。
     * [修复 2026-10-20] 细化：只有"像会话失效"的响应才返回 null（调用方据此提示重新登录）；
     * 其它业务拒绝（如“请从微信小程序重新进入打卡应用后提交”）由 isSessionInvalidMsg 区分，
     * 不需要据此判为登录过期（此前会误报“登录已过期”）。
     */
    private fun parseTodayChecked(resp: org.json.JSONObject): Boolean? {
        val data = resp.optJSONObject("data")
        if (data != null) return data.optString("recordStatusCode") == "CHECKED_IN"
        // 无 data → 无法判定；由调用方用 isSessionInvalidMsg 决定是否提示重新登录
        return null
    }

    /** [修复 2026-10-20] 响应是否属于「会话失效」（只看明确的登录类措辞） */
    private fun isSessionInvalidMsg(msg: String): Boolean {
        if (msg.isBlank()) return false
        val keys = listOf("登录无效", "未登录", "请先登录", "登录已过期", "会话已失效", "session", "token")
        return keys.any { msg.contains(it, ignoreCase = true) }
    }

    /**
     * [修复 2026-10-19] 打开应用时的服务端状态探测（取代 reconcileCheckedState）：
     *  - 网络失败：不做任何判定（避免误伤）
     *  - 响应异常/会话失效：提示一次重新登录（每会话一次）并记日志，不反复打扰
     *  - 响应正常：双向校正"今日已完成"标记
     *    · 服务端已打卡而本地未标记 → 补记（如在引擎侧打卡成功，主应用不再重复尝试）
     *    · 服务端未打卡而本地已标记 → 清除并恢复打卡任务（误报残留）
     * 30 秒节流，避免频繁 onResume 反复请求。
     */
    private fun probeServerState() {
        val cookie = Prefs.getCookie(this)
        if (cookie.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < 30_000L) return
        lastProbeAt = now
        lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) {
                runCatching { CheckinApi(cookie).getToday() }.getOrNull()
            } ?: return@launch // 网络失败：不做判定
            val decided = parseTodayChecked(resp)
            if (decided == null) {
                // [修复 2026-10-20] 只有明确的会话失效才提示重新登录；其它业务拒绝（如来源校验）
                // 仅记日志，避免把“服务端拒绝打卡”误报成“登录已过期”
                val msg = resp.optString("msg")
                if (isSessionInvalidMsg(msg)) {
                    if (!sessionInvalidPrompted) {
                        sessionInvalidPrompted = true
                        LogStore.append(this@MainActivity, "[系统] 登录态失效：$msg")
                        Toast.makeText(this@MainActivity, "登录已过期，请重新登录", Toast.LENGTH_LONG).show()
                        refreshStatus()
                    }
                } else {
                    LogStore.append(
                        this@MainActivity,
                        "[系统] 状态查询被服务端拒绝（非登录问题）：" +
                            "code=" + resp.optInt("code", -1) + " msg=" + msg
                    )
                }
                return@launch
            }
            sessionInvalidPrompted = false
            val localChecked = Prefs.isCheckedToday(this@MainActivity)
            if (decided && !localChecked) {
                Prefs.markCheckedToday(this@MainActivity)
                Prefs.setLastResult(this@MainActivity, "今日已完成打卡（服务端确认）")
                LogStore.append(this@MainActivity, "[系统] 服务端显示今日已打卡，已同步本地状态")
                AlarmScheduler.scheduleDaily(this@MainActivity)
                refreshStatus()
                // [修复 2026-10-19] 标记补记后立即同步引擎（引擎可撤今日网格）
                syncEngineStateNow()
            } else if (!decided && localChecked) {
                Prefs.clearCheckedToday(this@MainActivity)
                Prefs.setLastResult(this@MainActivity, "此前打卡未生效，已恢复自动打卡")
                LogStore.append(
                    this@MainActivity,
                    "[系统] 检测到此前打卡未生效（服务端无记录），已清除标记并恢复任务"
                )
                AlarmScheduler.ensureScheduled(this@MainActivity)
                refreshStatus()
                maybeRunForegroundCatchUp()
                // [修复 2026-10-19] 清除标记后立即同步引擎（引擎可恢复今日巡检）
                syncEngineStateNow()
            }
        }
    }

    /**
     * [修复 2026-10-17] 「打开即补打」：应用处于打卡窗口、已登录、今天还未完成时，
     * 立即在前台执行一次打卡（先查状态，已打卡不会重复提交），
     * 替代旧的"打开→安排 1 分钟后闹钟→再打开又推后 1 分钟"的死循环。
     */
    private fun maybeRunForegroundCatchUp() {
        if (catchUpRunning) return
        if (!Prefs.isServiceEnabled(this)) return
        if (Prefs.getCookie(this).isBlank()) return
        if (!AlarmScheduler.isInWindowNow(this)) return
        if (Prefs.isCheckedToday(this)) return
        val now = System.currentTimeMillis()
        if (now - lastCatchUpAt < 2 * 60_000L) return
        lastCatchUpAt = now
        catchUpRunning = true
        binding.progressBusy.visibility = View.VISIBLE
        LogStore.append(this, "[系统] 检测到窗口内未打卡，自动补打…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CheckinRunner.run(this@MainActivity) }
            withContext(Dispatchers.IO) {
                CheckinRunner.rescheduleAfterRun(this@MainActivity, result)
                CheckinRunner.syncExtras(this@MainActivity)
                // [修复 2026-10-19] 打完立即同步引擎：今日已完成标记 / 下次打卡文案及时到位
                syncEngineStateNow()
            }
            binding.progressBusy.visibility = View.GONE
            catchUpRunning = false
            Toast.makeText(this@MainActivity, CheckinRunner.userText(result), Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    /**
     * 调整时间窗口（菜单式）：手动设置 / 从服务端同步 / 重置默认
     * [手动设置 2026-10-18] 由原三按钮弹窗改为列表菜单，容纳三个操作
     */
    private fun showWindowMenu() {
        val source = when {
            Prefs.isWindowManual(this) -> getString(R.string.window_source_manual)
            Prefs.isWindowSynced(this) -> getString(R.string.window_source_remote)
            else -> getString(R.string.window_source_default)
        }
        val items = arrayOf(
            getString(R.string.window_item_manual),
            getString(R.string.window_item_sync),
            getString(R.string.window_item_reset)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.window_title_state, Prefs.windowLabel(this), source))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showWindowManualDialog()
                    1 -> syncWindowNow()
                    2 -> {
                        Prefs.resetWindow(this)
                        LogStore.append(this, getString(R.string.window_reset_done))
                        AlarmScheduler.ensureScheduled(this)
                        // [修复 2026-10-19] 窗口变更立即同步引擎（此前要等下一次 sync）
                        syncEngineStateNow()
                        refreshStatus()
                    }
                }
            }
            .show()
    }

    // [手动设置 2026-10-18] 原弹窗实现保留备查：
    // private fun showWindowMenu() {
    //     val source = getString(
    //         if (Prefs.isWindowSynced(this)) R.string.window_source_remote else R.string.window_source_default
    //     )
    //     MaterialAlertDialogBuilder(this)
    //         .setTitle(R.string.window_title)
    //         .setMessage(getString(R.string.window_msg, Prefs.windowLabel(this), source))
    //         .setPositiveButton(R.string.window_sync_btn) { _, _ -> syncWindowNow() }
    //         .setNeutralButton(R.string.window_reset_btn) { _, _ ->
    //             Prefs.resetWindow(this)
    //             LogStore.append(this, getString(R.string.window_reset_done))
    //             AlarmScheduler.ensureScheduled(this)
    //             refreshStatus()
    //         }
    //         .setNegativeButton(R.string.window_close, null)
    //         .show()
    // }

    /**
     * 手动设置打卡窗口首尾时间（方案 A）：
     * 两行（开始/结束）点击 → 系统时间选择器（Material 时钟样式，24 小时制）
     * [手动设置 2026-10-18] 保存后写入「手动锁定」，后台自动同步不再覆盖
     */
    private fun showWindowManualDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_window_manual, null)
        val tvStart = view.findViewById<TextView>(R.id.tvWindowStart)
        val tvEnd = view.findViewById<TextView>(R.id.tvWindowEnd)
        var startMin = Prefs.getWindowStartMinute(this)
        var endMin = Prefs.getWindowEndMinute(this)

        fun render() {
            tvStart.text = String.format(Locale.US, "%02d:%02d", startMin / 60, startMin % 60)
            tvEnd.text = String.format(Locale.US, "%02d:%02d", endMin / 60, endMin % 60)
        }
        render()

        fun pick(current: Int, titleRes: Int, onPicked: (Int) -> Unit) {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(current / 60)
                .setMinute(current % 60)
                .setTitleText(titleRes)
                .build()
            picker.addOnPositiveButtonClickListener {
                onPicked(picker.hour * 60 + picker.minute)
            }
            picker.show(supportFragmentManager, "window_time_picker")
        }

        view.findViewById<View>(R.id.rowWindowStart).setOnClickListener {
            pick(startMin, R.string.window_manual_pick_start) { startMin = it; render() }
        }
        view.findViewById<View>(R.id.rowWindowEnd).setOnClickListener {
            pick(endMin, R.string.window_manual_pick_end) { endMin = it; render() }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.window_manual_title)
            .setView(view)
            .setPositiveButton(R.string.manual_save, null)
            .setNegativeButton(R.string.map_cancel, null)
            .create()
        // 校验不通过时保持弹窗打开，便于直接修改
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (endMin - startMin < 5) {
                    Toast.makeText(this, R.string.window_manual_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setWindowManual(this, startMin, endMin)
                    val label = Prefs.windowLabel(this)
                    LogStore.append(this, getString(R.string.window_manual_done, label))
                    AlarmScheduler.ensureScheduled(this)
                    // [修复 2026-10-19] 窗口变更立即同步引擎
                    syncEngineStateNow()
                    refreshStatus()
                    Toast.makeText(this, getString(R.string.window_manual_done, label), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
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
                // [手动设置 2026-10-18] 显式同步：覆盖手动值并解除锁定
                // 原实现：RemoteSync.refreshSchedule(this@MainActivity, CheckinApi(cookie))
                RemoteSync.syncScheduleNow(this@MainActivity, CheckinApi(cookie))
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
            // [修复 2026-10-19] 窗口变更立即同步引擎
            syncEngineStateNow()
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
                        // [修复 2026-10-19] 坐标变化立即同步引擎
                        syncEngineStateNow()
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
                    // [修复 2026-10-19] 坐标变化立即同步引擎
                    syncEngineStateNow()
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
                // [修复 2026-10-19] 坐标变化立即同步引擎
                syncEngineStateNow()
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

    // [改进 2026-10-18] 原实现保留备查（旧版：顺序引导，一次只处理一项，无总览）：
    // private fun openPermissionGuides() {
    //     // 0) [修复 2026-10-18] 通知权限（Android 13+）：没有它收不到打卡结果提醒
    //     if (Build.VERSION.SDK_INT >= 33 &&
    //         ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    //     ) {
    //         LogStore.append(this, "[权限] 需要授予「通知」权限（用于接收打卡结果提醒）")
    //         Toast.makeText(this, R.string.permission_notify_needed, Toast.LENGTH_SHORT).show()
    //         ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    //         return
    //     }
    //     // 1) 精确闹钟权限（Android 12/13 需要；14+ 已在清单声明 USE_EXACT_ALARM 默认授予）
    //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
    //         LogStore.append(this, "[权限] 需要授予「精确闹钟」权限")
    //         startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    //         return
    //     }
    //     // 2) 电池优化白名单（国产 ROM 还需在系统设置里允许自启动/后台运行）
    //     val pm = getSystemService(POWER_SERVICE) as PowerManager
    //     if (!pm.isIgnoringBatteryOptimizations(packageName)) {
    //         LogStore.append(this, "[权限] 建议加入电池优化白名单")
    //         @Suppress("BatteryLife")
    //         startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    //         return
    //     }
    //     LogStore.append(this, "[权限] 已就绪")
    //     Toast.makeText(this, "权限已就绪", Toast.LENGTH_SHORT).show()
    // }

    /**
     * [改进 2026-10-18] 权限与后台运行检查（总览式）：
     * 一次列出所有可自动检测的项目及当前状态，点击对应项直接去修复；
     * 「自启动 / 后台运行」系统不提供检测接口，提供手动配置说明。
     */
    private fun openPermissionGuides() {
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifMainOk = notifGranted && nm.areNotificationsEnabled()
        // [修复 2026-10-19] 双包后"通知能力"由两个应用分别持有：打卡结果/常驻通知由「后台组件」发出。
        // 此前只查本应用 → 用户给过后台组件的通知权限时，这里会显示"未开启"（用户反馈的"给了却说没给"）。
        val engineInstalled = EngineBridge.query(this).installed
        val engineNotifGranted = Build.VERSION.SDK_INT < 33 || !engineInstalled || runCatching {
            packageManager.checkPermission(
                Manifest.permission.POST_NOTIFICATIONS, EngineBridge.ENGINE_PACKAGE
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val notifOk = notifMainOk && engineNotifGranted
        val alarmOk = AlarmScheduler.canScheduleExact(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        LogStore.append(
            this,
            "[权限] 检查：通知=${if (notifOk) "通过" else "未配置"}" +
                "（本应用${if (notifMainOk) "✓" else "✗"} · 组件" +
                "${if (!engineInstalled) "未安装" else if (engineNotifGranted) "✓" else "✗"}）；" +
                "精确闹钟=${if (alarmOk) "通过" else "未配置"}；" +
                "电池优化=${if (batteryOk) "通过" else "未配置"}"
        )

        val items = arrayOf(
            getString(if (notifOk) R.string.perm_notify_ok else R.string.perm_notify_bad),
            getString(if (alarmOk) R.string.perm_alarm_ok else R.string.perm_alarm_bad),
            getString(if (batteryOk) R.string.perm_battery_ok else R.string.perm_battery_bad),
            getString(R.string.perm_autostart_entry)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_check_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> when {
                        !notifGranted -> requestNotificationPermissionIfNeeded()
                        !nm.areNotificationsEnabled() -> startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                        // [修复 2026-10-19] 本应用已就绪、后台组件缺通知权限 → 直接打开组件的通知设置页
                        engineInstalled && !engineNotifGranted -> runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, EngineBridge.ENGINE_PACKAGE)
                            )
                        }.onFailure {
                            Toast.makeText(
                                this,
                                "未能打开「后台组件」的通知设置，请在系统设置中手动开启",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> toastPermDone()
                    }
                    1 -> if (alarmOk) toastPermDone() else startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                    2 -> if (batteryOk) toastPermDone() else {
                        @Suppress("BatteryLife")
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        )
                    }
                    3 -> showAutostartTips()
                }
            }
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    private fun toastPermDone() {
        Toast.makeText(this, R.string.perm_item_done, Toast.LENGTH_SHORT).show()
    }

    /** 自启动 / 后台运行的手动配置说明（系统不提供检测接口） */
    private fun showAutostartTips() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_autostart_entry)
            .setMessage(R.string.perm_autostart_tips)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    // [双包 2026-10-18] 后台组件（引擎）相关
    // ─────────────────────────────────────────────────────────────

    /** 启动时检测：未安装/需更新 → 引导安装（首次引导未看过时先不弹，避免弹窗叠加） */
    private fun maybePromptEngineInstall() {
        if (!Prefs.isFirstRunGuideShown(this)) return
        val info = EngineBridge.query(this)
        if (!info.needsInstall) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.engine_prompt_title)
            .setMessage(R.string.engine_prompt_msg)
            .setPositiveButton(R.string.engine_install) { _, _ -> installEngineFlow() }
            .setNegativeButton(R.string.map_cancel, null)
            .show()
    }

    /** 后台组件（引擎）菜单：安装/更新 + 连通与测试指令 */
    private fun showEngineMenu() {
        val info = EngineBridge.query(this)
        val status = when {
            !info.installed -> getString(R.string.engine_status_missing)
            info.installedVersionCode < info.bundledVersionCode ->
                getString(R.string.engine_status_outdated, info.installedVersionCode, info.bundledVersionCode)
            // [修复 2026-10-18] 版本号相同但内容不一致（哈希检测）：也提示可重装
            info.contentDiffers -> getString(R.string.engine_status_content_differs, info.installedVersionCode)
            else -> getString(R.string.engine_status_installed, info.installedVersionCode)
        }
        // [分轨 2026-10-19] 测试功能仅保留在调试版；正式版只提供「安装 / 更新后台组件」
        val items = if (BuildConfig.DEBUG) {
            arrayOf(
                getString(R.string.engine_install),
                getString(R.string.engine_test_alarm),
                getString(R.string.engine_ping),
                getString(R.string.engine_reinstall)
            )
        } else {
            arrayOf(getString(R.string.engine_install))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.engine_menu_title, status))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> installEngineFlow()
                    // [分轨 2026-10-19] 下面 1/2/3 均为测试用入口：再包一层常量守卫，
                    // 让 R8 在正式包里把整段剔除（正式版菜单里本来也不会出现这几项）
                    1 -> if (BuildConfig.DEBUG) {
                        // 保险：先尝试拉起引擎引导页（解除 stopped 状态），再发指令
                        EngineBridge.activateEngine(this)
                        EngineBridge.sendCommand(this, "schedule_test") { it.putExtra("minutes", 2) }
                        Toast.makeText(this, R.string.engine_sent_test, Toast.LENGTH_LONG).show()
                        LogStore.append(this, "[引擎] 已发送测试闹钟指令（2 分钟后触发）")
                    }
                    2 -> if (BuildConfig.DEBUG) {
                        EngineBridge.activateEngine(this)
                        EngineBridge.sendCommand(this, "ping")
                        Toast.makeText(this, R.string.engine_sent_ping, Toast.LENGTH_SHORT).show()
                        LogStore.append(this, "[引擎] 已发送连通测试")
                    }
                    3 -> if (BuildConfig.DEBUG) reinstallEngineFlow()
                }
            }
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    /** 安装流程：先检查「安装未知应用」权限，再拉起系统安装器 */
    private fun installEngineFlow() {
        if (!EngineBridge.canInstall(this)) {
            pendingUnknownSources = true // [双包 2026-10-18] 授权返回后自动继续安装
            Toast.makeText(this, R.string.engine_need_unknown_source, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }
            return
        }
        if (EngineBridge.installOrUpdate(this)) {
            awaitingEngineInstall = true // [修复 2026-10-18] 回到前台时校验是否真的安装成功
            LogStore.append(this, "[引擎] 已拉起后台组件安装（请在系统弹窗中确认）")
        } else {
            Toast.makeText(this, R.string.engine_install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * [修复 2026-10-18] 卸载后重装：用于"更新一直装不上/签名冲突"的修复场景。
     * 卸载完成后回到应用会自动重新安装内嵌引擎。
     */
    private fun reinstallEngineFlow() {
        val info = EngineBridge.query(this)
        if (!info.installed) {
            installEngineFlow()
            return
        }
        val ok = runCatching {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${EngineBridge.ENGINE_PACKAGE}"))
            )
        }.isSuccess
        if (ok) {
            pendingEngineReinstall = true
            reinstallRequestedAt = System.currentTimeMillis()
            LogStore.append(this, "[引擎] 已拉起卸载界面（确认卸载后将自动重装内嵌引擎）")
        } else {
            Toast.makeText(this, R.string.engine_install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * [双包 2026-10-18] 安装流程续接（每次回到前台检查）：
     *  1) 刚去授权"安装未知应用"且已授权 → 自动拉起安装器（不再需要用户再点一次菜单）
     *  2) 引擎已安装但尚未激活过 → 拉起引导页（解除 stopped 状态 + 申请权限）
     */
    private fun resumeEngineInstallFlow() {
        val info = EngineBridge.query(this)
        if (pendingUnknownSources) {
            pendingUnknownSources = false
            if (EngineBridge.canInstall(this)) installEngineFlow()
            return
        }
        // [修复 2026-10-18] 校验"发起的安装是否真的生效"：此前安装被取消/被拦截时毫无提示，
        // 用户会误以为已更新（实际引擎一直停在旧版本）
        if (awaitingEngineInstall) {
            awaitingEngineInstall = false
            if (info.installedVersionCode >= info.bundledVersionCode) {
                Toast.makeText(
                    this,
                    getString(R.string.engine_update_ok, info.installedVersionCode),
                    Toast.LENGTH_SHORT
                ).show()
                LogStore.append(this, "[引擎] 后台组件已更新到 v${info.installedVersionCode}")
            } else {
                val cur = if (info.installed) "v${info.installedVersionCode}" else "未安装"
                Toast.makeText(
                    this,
                    getString(R.string.engine_update_not_applied, cur, info.bundledVersionCode),
                    Toast.LENGTH_LONG
                ).show()
                LogStore.append(
                    this,
                    "[引擎] 警告：更新未生效（当前 $cur，目标 v${info.bundledVersionCode}）"
                )
            }
        }
        // [修复 2026-10-18] "卸载后重装"：旧组件已卸载 → 自动重装内嵌引擎
        if (pendingEngineReinstall) {
            if (!info.installed) {
                pendingEngineReinstall = false
                LogStore.append(this, "[引擎] 旧组件已卸载，开始重新安装内嵌引擎")
                installEngineFlow()
                return
            }
            // 60 秒内继续等待卸载完成；超时视为用户取消了卸载
            if (System.currentTimeMillis() - reinstallRequestedAt > 60_000L) {
                pendingEngineReinstall = false
            }
        }
        if (!info.installed) return
        // [双包 2026-10-18] 先同步状态再激活：确保引导页启动常驻服务时开关已就绪
        syncEngineState()
        // [v7 巡检链] 打开应用即请求引擎补巡检一次（引擎内部 90 秒节流，避免频繁打开反复提交）
        EngineBridge.sendCommand(this, "patrol_now")
        // [修复 v12] 每次打开应用做一次"活动唤醒"：广播无法唤醒被系统冻结/停掉的引擎时，
        // 用前台活动启动来恢复引擎常驻服务（每会话只做一次，避免与引导页互相拉起）
        val versionChanged = Prefs.getEngineActivatedVersion(this) != info.installedVersionCode
        if (!engineActivatedThisSession || versionChanged) {
            engineActivatedThisSession = true
            EngineBridge.activateEngine(this)
        }
        if (versionChanged) {
            Prefs.setEngineActivatedVersion(this, info.installedVersionCode)
            LogStore.append(this, "[引擎] 后台组件已就绪（v${info.installedVersionCode}），已完成初始化")
            Toast.makeText(this, R.string.engine_activated, Toast.LENGTH_SHORT).show()
            // [双包 2026-10-18] 自动 ping：立即给一个"引擎已接通"的可视信号（引擎会回一条通知）
            // [分轨 2026-10-19] 仅调试版执行；正式版静默（不向用户展示测试类通知）
            if (BuildConfig.DEBUG) EngineBridge.sendCommand(this, "ping")
        }
    }
}

/**
 * 兼容「链接点击 + 文本长按选择」的移动方法：
 * - 轻点（短于长按阈值）落在链接上 → 打开链接
 * - 其余手势（长按选择、拖动选择等）→ 交给系统默认的 ArrowKeyMovementMethod 处理
 */
private class SelectableLinkMovementMethod : ArrowKeyMovementMethod() {

    private var downTime = 0L

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downTime = event.eventTime
            MotionEvent.ACTION_UP -> {
                val quickTap = event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                if (quickTap && openLinkIfHit(widget, buffer, event)) return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    /** 命中链接则打开；返回是否命中 */
    private fun openLinkIfHit(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val layout = widget.layout ?: return false
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val link = buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull() ?: return false
        link.onClick(widget)
        return true
    }
}
