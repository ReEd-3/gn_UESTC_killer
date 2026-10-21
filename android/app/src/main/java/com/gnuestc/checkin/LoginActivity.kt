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

    /**
     * [修复 2026-10-21] 已验证失败的 cookie 值：轮询每隔 3.2 秒一次，
     * 若对同一个无效 cookie 反复验证，6 分钟内会发出近百次请求（且有触发风控风险）。
     * 同值失败后不再自动重试，直到 cookie 变化或用户手动点击（force）。
     */
    private val failedCookies = mutableSetOf<String>()

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

        // [修复 2026-10-21] 会话已失效/未登录时，先清空 WebView 的 cookie：
        // 否则登录页首次加载时 shouldInterceptRequest 会捕获到【登录前的旧 SESSION】，
        // 旧会话在新登录生效后才被作废 → 造成"验证通过→保存→立即失效"的假登录（实测根因）
        val needCleanCookies = Prefs.getCookie(this).isBlank() || !Prefs.isSessionValid(this)

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
        // [修复 2026-10-21] 先真正清完 cookie 再加载（removeAllCookies 是异步的，
        // 原实现 flush 后立即 loadUrl，首屏请求仍可能带着旧 cookie → 旧会话又被观测到）
        if (needCleanCookies) {
            CookieManager.getInstance().removeAllCookies {
                runCatching { CookieManager.getInstance().flush() }
                observedCookie = null
                binding.webView.loadUrl(HOME)
            }
        } else {
            observedCookie = null
            binding.webView.loadUrl(HOME)
        }

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
     * 读取会话并验证。
     * [修复 2026-10-21] 原实现优先用 observedCookie（旁路捕获）：它可能是【登录前】的旧会话，
     * 而旧会话会在新登录生效后被服务端立即作废 → “验证通过→保存→马上失效”的假登录。
     * 现在：先取 CookieManager 当前值（登录后的新会话），observedCookie 只作为候选，
     * 逐个验证取第一个有效的；保存后再用“实际存下的值”复验一次。
     */
    private fun tryCapture(force: Boolean = false, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        if (verifying || reading || (!force && now - lastVerifyAt < 3000)) return

        reading = true
        val jarCookie = runCatching {
            CookieManager.getInstance().let { it.flush(); it.getCookie(HOME) }.orEmpty()
        }.getOrDefault("")
        reading = false

        val candidates = listOf(jarCookie, observedCookie.orEmpty())
            .filter { it.contains("SESSION=") }
            .distinct()
            // [修复 2026-10-21] 已验证失败过的同一个 cookie 不再重复验证（force=true 时除外）
            .filter { force || it !in failedCookies }

        if (candidates.isEmpty()) {
            if (!quiet) binding.tvLoginStatus.text = getString(R.string.login_status_not_yet)
            return
        }
        verifyCandidates(candidates, now)
    }

    /** [修复 2026-10-21] 逐个候选 cookie 验证，第一个有效即采用；保存后再复验存下的值 */
    private fun verifyCandidates(candidates: List<String>, now: Long) {
        if (verifying) return
        lastVerifyAt = now
        verifying = true
        binding.tvLoginStatus.text = getString(R.string.login_status_verifying)

        lifecycleScope.launch {
            // 返回：是否成功 / cookie（或错误信息）/ 学生姓名
            val result: Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
                var lastErr = "未获取到有效会话"
                for (candidate in candidates) {
                    val pair = runCatching {
                        val resp = CheckinApi(candidate).getToday()
                        val code = resp.optInt("code", -1)
                        if (code != 0) {
                            lastErr = "服务端返回 code=$code ${resp.optString("msg")}"
                            return@runCatching null
                        }
                        val data = resp.optJSONObject("data")
                        if (data == null) {
                            lastErr = "服务端未返回账号信息"
                            return@runCatching null
                        }
                        // 验证通过：顺便同步本账号打卡点与时间窗口（失败不影响登录）
                        val api = CheckinApi(candidate)
                        RemoteSync.refreshLocation(this@LoginActivity, api)
                        RemoteSync.refreshSchedule(this@LoginActivity, api)
                        candidate to data.optString("studentName").ifBlank { "（已登录）" }
                    }.getOrElse { e ->
                        lastErr = "${e.javaClass.simpleName}: ${e.message}"
                        null
                    }
                    if (pair != null) return@withContext Triple(true, pair.first, pair.second)
                    // [修复 2026-10-21] 记录失败值，避免轮询对同一个无效 cookie 反复请求
                    failedCookies += candidate
                }
                Triple(false, lastErr, "")
            }
            verifying = false

            if (!result.first) {
                binding.tvLoginStatus.text =
                    getString(R.string.login_status_verify_failed, result.second.take(80))
                return@launch
            }

            val cookie = result.second
            val name = result.third
            CookieManager.getInstance().flush()
            Prefs.setCookie(this@LoginActivity, cookie)

            // [修复 2026-10-21] 关键：用“实际存下来并解密得到的 cookie”再验证一次，
            // 避免存下一条已经被新登录顶掉的旧会话
            val storedOk = withContext(Dispatchers.IO) {
                runCatching {
                    CheckinApi(Prefs.getCookie(this@LoginActivity)).getToday().optInt("code", -1) == 0
                }.getOrDefault(false)
            }
            if (!storedOk) {
                Prefs.setCookie(this@LoginActivity, "")
                Prefs.setSessionValid(this@LoginActivity, false)
                LogStore.append(this@LoginActivity, "[登录] 保存后复验失败：会话可能已被新登录作废")
                binding.tvLoginStatus.text =
                    getString(R.string.login_status_verify_failed, "保存后复验失败，请重新登录")
                return@launch
            }

            // [修复 2026-10-21] 登录成功 → 会话恢复有效
            failedCookies.clear()
            Prefs.setSessionValid(this@LoginActivity, true)
            LogStore.append(this@LoginActivity, "[登录] 登录成功：$name")
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
            // [修复 2026-10-20] 登录成功即刷新「最近打卡」结果，避免卡片继续展示上一次遗留的
            // “登录已过期/登录失败”等陈旧信息（用户反馈：已登录却显示登录失败）
            Prefs.setLastResult(this@LoginActivity, "登录成功，等待自动打卡")
            // 立即安排：未到点排窗口开始时间；窗口内则 1 分钟后尽快补打
            AlarmScheduler.ensureScheduled(this@LoginActivity)
            // [修复 2026-10-19] 登录成功立即把新会话同步给引擎：
            // 否则返回主界面只走 onResume 时，引擎可能仍持旧/空 Cookie →
            // 巡检报“登录无效”、常驻通知的“下次打卡”文案滞后
            EngineBridge.syncState(this@LoginActivity)
            Toast.makeText(
                this@LoginActivity,
                getString(R.string.login_success, name),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    companion object {
        // 首页未登录会自动 302 到 idas 统一身份认证；登录后回到本域名即可捕获 SESSION
        private const val HOME = "https://jzsz.uestc.edu.cn/"
        private const val HOST = "jzsz.uestc.edu.cn"
    }
}
