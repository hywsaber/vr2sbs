package com.vr2sbs.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vr2sbs.R
import com.vr2sbs.bilibili.BilibiliApiClient
import com.vr2sbs.bilibili.BilibiliCookieStore
import com.vr2sbs.bilibili.BilibiliNav
import com.vr2sbs.databinding.ActivityBilibiliLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Embedded Bilibili sign-in. The session is stored by WebView's cookie manager and reused
 * by [com.vr2sbs.bilibili.BilibiliApiClient] to request the highest available video quality.
 */
class BilibiliLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBilibiliLoginBinding
    private val cookieStore = BilibiliCookieStore()
    private var loggedIn = false
    private var loginPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBilibiliLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bilibiliLoginBackButton.setOnClickListener { finishWithResult() }
        binding.bilibiliLoginDoneButton.setOnClickListener { finishWithResult() }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        configureWebView()
        if (savedInstanceState != null) {
            loggedIn = savedInstanceState.getBoolean(STATE_LOGGED_IN, false)
            binding.bilibiliLoginWebView.restoreState(savedInstanceState)
            if (loggedIn) {
                binding.bilibiliLoginProgress.visibility = View.GONE
                binding.bilibiliLoginDoneButton.isEnabled = true
                binding.bilibiliLoginStatusText.text =
                    getString(R.string.bilibili_login_signed_in)
            }
        } else {
            binding.bilibiliLoginWebView.loadUrl(LOGIN_URL)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!loggedIn) {
            startLoginPolling()
        }
    }

    override fun onStop() {
        loginPollJob?.cancel()
        loginPollJob = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LOGGED_IN, loggedIn)
        binding.bilibiliLoginWebView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.bilibiliLoginWebView.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.bilibiliLoginWebView, true)
        }
        binding.bilibiliLoginWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // The desktop sign-in page only offers QR login, which needs a second device.
            // A mobile UA gets the H5 page with password and SMS sign-in.
            userAgentString = LOGIN_USER_AGENT
        }
        binding.bilibiliLoginWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                // Keep Bilibili's pages inside the WebView and block app deep links.
                return scheme != null && scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank() || loggedIn) {
                    return
                }
                binding.bilibiliLoginStatusText.text =
                    getString(R.string.bilibili_login_page_hint)
                checkLoginOnce()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !loggedIn) {
                    binding.bilibiliLoginStatusText.text =
                        getString(R.string.bilibili_login_page_failed)
                    binding.bilibiliLoginProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun startLoginPolling() {
        if (loginPollJob != null) {
            return
        }
        binding.bilibiliLoginProgress.visibility = View.VISIBLE
        loginPollJob = lifecycleScope.launch {
            while (isActive) {
                val nav = fetchNav()
                if (nav?.isLoggedIn == true) {
                    renderSignedIn(nav)
                    break
                }
                delay(LOGIN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun checkLoginOnce() {
        lifecycleScope.launch {
            val nav = fetchNav()
            if (nav?.isLoggedIn == true) {
                renderSignedIn(nav)
            }
        }
    }

    private suspend fun fetchNav(): BilibiliNav? = withContext(Dispatchers.IO) {
        runCatching {
            BilibiliApiClient(cookieHeaderProvider = cookieStore::cookieHeader).fetchNav()
        }.getOrNull()
    }

    private fun renderSignedIn(nav: BilibiliNav?) {
        if (loggedIn) {
            return
        }
        loggedIn = true
        loginPollJob?.cancel()
        loginPollJob = null
        cookieStore.flush()
        binding.bilibiliLoginProgress.visibility = View.GONE
        binding.bilibiliLoginDoneButton.isEnabled = true
        binding.bilibiliLoginStatusText.text = signedInMessage(nav)
        setResult(RESULT_OK)
    }

    private fun signedInMessage(nav: BilibiliNav?): String {
        val userName = nav?.userName
        if (userName.isNullOrBlank()) {
            return getString(R.string.bilibili_login_signed_in)
        }
        return getString(R.string.bilibili_login_signed_in_hint, userName)
    }

    private fun finishWithResult() {
        if (loggedIn) {
            setResult(RESULT_OK)
        }
        finish()
    }

    companion object {
        private const val LOGIN_URL = "https://passport.bilibili.com/login"
        private const val LOGIN_POLL_INTERVAL_MS = 4_000L
        private const val STATE_LOGGED_IN = "bilibili_logged_in"
        private const val LOGIN_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
