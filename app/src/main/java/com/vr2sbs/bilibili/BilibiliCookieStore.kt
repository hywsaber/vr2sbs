package com.vr2sbs.bilibili

import android.webkit.CookieManager

/**
 * Shares the Bilibili session created by the embedded login page with the API and
 * media requests. Cookies live in WebView's persistent store on this device.
 */
class BilibiliCookieStore {
    fun cookieHeader(): String? =
        CookieManager.getInstance().getCookie(BILIBILI_HOME)?.takeIf { it.isNotBlank() }

    fun flush() {
        CookieManager.getInstance().flush()
    }

    private companion object {
        const val BILIBILI_HOME = "https://www.bilibili.com"
    }
}
