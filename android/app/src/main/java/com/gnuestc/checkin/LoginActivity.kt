package com.gnuestc.checkin

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gnuestc.checkin.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.Volatile

/**
 * 内嵌 WebView 完成校内 SSO 登录：
 * 用户在页面里完成一次登录后，从 CookieManager 读取 SESSION，
 * 调一次 /today 验证会话真实有效，通过后保存 Cookie 并安排定时。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var verifying = false
    private var reading = false
    private var lastVerifyAt = 0L
    private var verifyJob: Job? = null

    /** shouldInterceptRequest 旁路捕获到的 Cookie（页面请求里实际发送的值） */
    @Volatile
    private var observedCookie: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // [修复 2026-10-19] 主题已改由 App(Application) 统一应用；保留备查
        // ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15/16 强制边到边显示：给根布局留出状态栏间距
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 使用普通 Chrome UA，避免个别 SSO 页面拦截 WebView 标记（wv）
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains(HOST) == true) {
                    binding.tvLoginStatus.text = getString(R.string.login_status_waiting)
                    // 回到主站后：立即尝试一次，并确保轮询在运行
                    startAutoVerify()
                    tryCapture()
                } else {
                    binding.tvLoginStatus.text = getString(R.string.login_status_sso)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.tvLoginStatus.text = getString(R.string.login_status_error)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // 旁路观察：读取页面请求头里实际携带的 Cookie（仅记录，不拦截）
                try {
                    val u = request?.url?.toString().orEmpty()
                    if (u.contains(HOST)) {
                        val headers = request?.requestHeaders
                        val c = headers?.get("Cookie") ?: headers?.get("cookie")
                        if (!c.isNullOrBlank() && c.contains("SESSION=")) observedCookie = c
                    }
                } catch (_: Exception) {
                }
                return null
            }
        }

        binding.btnDone.setOnClickListener {
            startAutoVerify()
            tryCapture(force = true)
        }

        // 从站点首页进入：未登录会自动 302 到学校统一身份认证（CAS）登录页
        binding.webView.loadUrl(HOME)

        // 登录期间后台轮询会话：WebView 的 Cookie 入库有延迟，且登录后回到的是单页应用，
        // 页面不再产生「加载完成」事件，只靠 onPageFinished 一次性检查容易漏检
        startAutoVerify()
    }

    /**
     * 周期性会话检测：每 3 秒检查一次 Cookie，检测到 SESSION 就尝试验证，
     * 最多跑约 6 分钟；登录过程中不会干扰状态提示（无会话时静默跳过）。
     */
    private fun startAutoVerify() {
        if (verifyJob?.isActive == true) return
        verifyJob = lifecycleScope.launch {
            repeat(120) {
                if (isFinishing || isDestroyed) return@launch
                tryCapture(quiet = true)
                delay(3200)
            }
            if (!isFinishing && !isDestroyed) {
                binding.tvLoginStatus.text = getString(R.string.login_status_timeout)
            }
        }
    }

    /**
     * 读取会话并验证。优先使用 shouldInterceptRequest 旁路捕获的 Cookie
     * （页面实际发请求时携带的值，不依赖 CookieManager 的读取时机），
     * 否则退回 CookieManager.getCookie。
     */
    private fun tryCapture(force: Boolean = false, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        if (verifying || reading || (!force && now - lastVerifyAt < 3000)) return

        observedCookie?.let { c ->
            onCookieReady(c, now, quiet)
            return
        }

        reading = true
        val cookie = CookieManager.getInstance().getCookie(HOME).orEmpty()
        reading = false
        onCookieReady(cookie, now, quiet)
    }

    /** 拿到 Cookie 后的处理：校验后发起 /today 验证 */
    private fun onCookieReady(cookie: String, now: Long, quiet: Boolean) {
        if (verifying) return
        if (!cookie.contains("SESSION=")) {
            if (!quiet) binding.tvLoginStatus.text = getString(R.string.login_status_not_yet)
            return
        }

        lastVerifyAt = now
        verifying = true
        binding.tvLoginStatus.text = getString(R.string.login_status_verifying)

        lifecycleScope.launch {
            val verify = withContext(Dispatchers.IO) {
                runCatching {
                    val resp = CheckinApi(cookie).getToday()
                    val code = resp.optInt("code", -1)
                    if (code == 0) {
                        // 提取本账号打卡点与时间窗口（服务端按学生下发），失败不影响登录
                        val api = CheckinApi(cookie)
                        RemoteSync.refreshLocation(this@LoginActivity, api)
                        RemoteSync.refreshSchedule(this@LoginActivity, api)
                        resp.optJSONObject("data")?.optString("studentName").orEmpty()
                    } else {
                        "ERR:服务端返回 code=$code ${resp.optString("msg")}"
                    }
                }.getOrElse { "ERR:${it.javaClass.simpleName}: ${it.message}" }
            }
            verifying = false

            if (verify.isNotEmpty() && !verify.startsWith("ERR:")) {
                CookieManager.getInstance().flush()
                Prefs.setCookie(this@LoginActivity, cookie)
                LogStore.append(this@LoginActivity, "[登录] 登录成功：$verify")
                if (Prefs.isDefaultPointKnown(this@LoginActivity)) {
                    LogStore.append(
                        this@LoginActivity,
                        "[登录] 已获取本账号打卡点：${Prefs.getDefaultName(this@LoginActivity)}"
                    )
                }
                if (Prefs.isWindowSynced(this@LoginActivity)) {
                    LogStore.append(
                        this@LoginActivity,
                        "[登录] 已同步时间窗口（${Prefs.windowLabel(this@LoginActivity)}）"
                    )
                }
                // 立即安排：未到点排窗口开始时间；窗口内则 1 分钟后尽快补打
                AlarmScheduler.ensureScheduled(this@LoginActivity)
                // [修复 2026-10-19] 登录成功立即把新会话同步给引擎：
                // 否则返回主界面只走 onResume 时，引擎可能仍持旧/空 Cookie →
                // 巡检报“登录无效”、常驻通知的“下次打卡”文案滞后
                EngineBridge.syncState(this@LoginActivity)
                Toast.makeText(
                    this@LoginActivity,
                    getString(R.string.login_success, verify),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                binding.tvLoginStatus.text = if (verify.startsWith("ERR:")) {
                    getString(R.string.login_status_verify_failed, verify.removePrefix("ERR:").take(80))
                } else {
                    getString(R.string.login_status_not_yet)
                }
            }
        }
    }

    companion object {
        // 首页未登录会自动 302 到 idas 统一身份认证；登录后回到本域名即可捕获 SESSION
        private const val HOME = "https://jzsz.uestc.edu.cn/"
        private const val HOST = "jzsz.uestc.edu.cn"
    }
}
