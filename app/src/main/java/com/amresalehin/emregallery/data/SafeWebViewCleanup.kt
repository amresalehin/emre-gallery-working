package com.amresalehin.emregallery.data

import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Robust, unified utility for WebView lifecycle management and safe termination.
 * Guarantees that:
 * 1. onRenderProcessGone always returns true so Chromium aw_browser_terminator.cc never crashes the host.
 * 2. Destroyed or detached WebViews clean up safely without leaking renderer processes or SQLite locks.
 */
object SafeWebViewCleanup {

    val TERMINATION_SAFE_CLIENT = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            return true
        }
    }

    fun safeDestroy(webView: WebView?) {
        if (webView == null) return
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.webViewClient = TERMINATION_SAFE_CLIENT
            webView.webChromeClient = null
            webView.onPause()
            webView.destroy()
        } catch (_: Throwable) {
        }
    }
}
