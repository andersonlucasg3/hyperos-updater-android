package com.hyperos.updater.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Assisted WebView download capture: the user navigates the store page freely.
 * The app passively intercepts .apk/.apkm/.xapk/.apks URLs and known CDN
 * patterns, then returns the captured URL + replay headers to the caller
 * so the native OkHttp download engine can take over.
 */
class DownloadActivity : Activity() {

    private var captured = false
    private var currentPageUrl: String? = null
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val captureTimeout = 3 * 60 * 1000L // 3 minutes
    private val timeoutRunnable = Runnable { finishCapturing(null) }

    companion object {
        private const val TAG = "DownloadActivity"
        // Extra keys
        const val EXTRA_URL = "url"
        const val EXTRA_APP_NAME = "appName"
        const val EXTRA_DOWNLOAD_URL = "downloadUrl"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "userAgent"
        const val EXTRA_COOKIE = "cookie"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: ""
        Log.i(TAG, "Loading: $url${if (appName.isNotBlank()) " for $appName" else ""}")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top instruction bar ──────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 8)
            setBackgroundColor(Color.parseColor("#1a73e8"))
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = if (appName.isNotBlank())
                "Navegue até o download de $appName — o link é capturado automaticamente"
            else
                "Navegue até o download — o link é capturado automaticamente"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(label)
        val cancelBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(24, 4, 4, 4)
            setOnClickListener {
                Log.i(TAG, "User cancelled")
                finishCapturing(null)
            }
        }
        topBar.addView(cancelBtn)
        container.addView(topBar)

        // ── Tiny progress bar (hidden once page loads) ────────────
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                4
            )
        }
        container.addView(progressBar)

        // ── WebView ──────────────────────────────────────────────
        val userAgent = "Mozilla/5.0 (Linux; Android 16; Xiaomi 17 Pro Max) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.setSupportMultipleWindows(true)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )

            // ── Passive JS: intercept fetch/XHR URLs ──────────────
            evaluateJavascript(
                "(function(){" +
                // Override fetch
                "var _fetch=window.fetch;" +
                "window.fetch=function(url,opts){" +
                "if(typeof url==='string'&&_isDl(url)){window._apkm_dl_url=url;}" +
                "return _fetch.apply(this,arguments);" +
                "};" +
                // Override XHR open
                "var _open=XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open=function(m,u){" +
                "this.__url=m+' '+u;" +
                "return _open.apply(this,arguments);" +
                "};" +
                // Override XHR send
                "var _send=XMLHttpRequest.prototype.send;" +
                "XMLHttpRequest.prototype.send=function(b){" +
                "var s=this;" +
                "s.addEventListener('load',function(){" +
                "var u=s.responseURL||'';" +
                "if(_isDl(u)){window._apkm_dl_url=u;return;}" +
                "try{var j=JSON.parse(s.responseText||'{}');" +
                "var d=j.url||j.download_url||j.link||j.data;" +
                "if(typeof d==='string'&&d.length>10&&(d.startsWith('http')||d.startsWith('//'))){" +
                "var fd=d.startsWith('//')?'https:'+d:d;" +
                "if(_isDl(fd)){window._apkm_dl_url=fd;}" +
                "}}" +
                "catch(e){}" +
                "});" +
                "return _send.apply(this,arguments);" +
                "};" +
                // Helper: detect download URLs (strict: strip query first, real file
                // extensions or known file-CDNs only — never bare "/download" or "cdn",
                // which match ordinary page/API URLs and poison the capture)
                "function _isDl(u){" +
                "if(!u||typeof u!=='string')return false;" +
                "var p=u.toLowerCase().split(/[?#]/)[0];" +
                "return p.endsWith('.apk')||p.endsWith('.apkm')||p.endsWith('.xapk')||p.endsWith('.apks')||" +
                "p.indexOf('cloudflarestorage.com')>=0||p.indexOf('d.apkpure.com')>=0;" +
                "}" +
                "})()", null
            )
        }
        val wv = webView!!

        // ── DownloadListener ──────────────────────────────────────
        wv.setDownloadListener { downloadUrl, _, _, _, _ ->
            Log.i(TAG, "DownloadListener: $downloadUrl")
            if (isDownloadUrl(downloadUrl)) {
                capture(downloadUrl, wv)
            } else {
                Log.w(TAG, "DownloadListener URL ignored (not a file URL): $downloadUrl")
            }
        }

        // ── WebChromeClient ───────────────────────────────────────
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 80) progressBar.visibility = android.view.View.GONE
            }
        }

        // ── WebViewClient ─────────────────────────────────────────
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                if (pageUrl != null) currentPageUrl = pageUrl
                // Check JS-captured URL on every navigation start (strict filter:
                // only real file URLs, never the page itself or API endpoints)
                view?.evaluateJavascript("window._apkm_dl_url||''") { result ->
                    val clean = cleanJsResult(result)
                    if (clean != null && isDownloadUrl(clean)) {
                        Log.i(TAG, "JS captured (page start): $clean")
                        capture(clean, view)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                Log.i(TAG, "Page loaded: $finishedUrl")
                if (finishedUrl != null) currentPageUrl = finishedUrl
                progressBar.visibility = android.view.View.GONE
                // Check JS-captured URL after page load (strict filter)
                view?.evaluateJavascript("window._apkm_dl_url||''") { result ->
                    val clean = cleanJsResult(result)
                    if (clean != null && isDownloadUrl(clean)) {
                        Log.i(TAG, "JS captured (page finish): $clean")
                        capture(clean, view)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, requestUrl: String?): Boolean {
                if (requestUrl == null) return false
                Log.i(TAG, "Navigate: $requestUrl")

                // ── apkcombo.com/d?u=<base64> redirect ──────────────
                if (requestUrl.contains("apkcombo.com/d?") && requestUrl.contains("u=")) {
                    val uParam = Regex("[?&]u=([^&]+)").find(requestUrl)?.groupValues?.get(1)
                    if (uParam != null) {
                        try {
                            val decoded = String(Base64.decode(uParam, Base64.URL_SAFE), Charsets.UTF_8)
                            if (decoded.startsWith("http")) {
                                Log.i(TAG, "Decoded apkcombo CDN: $decoded")
                                capture(decoded, view)
                                return true
                            }
                        } catch (_: Exception) { }
                    }
                }

                // ── Uptodown download redirects ────────────────────
                if ((requestUrl.contains("uptodown.com") && requestUrl.contains("/download/"))) {
                    // Uptodown final download pages often redirect; let WebView follow
                    return false
                }

                // ── APK file extensions & known CDNs ───────────────
                if (isDownloadUrl(requestUrl) && requestUrl != url) {
                    Log.i(TAG, "CDN capture: $requestUrl")
                    capture(requestUrl, view)
                    return true
                }
                return false
            }
        }

        container.addView(wv)
        setContentView(container)
        wv.loadUrl(url)

        // Start timeout
        handler.postDelayed(timeoutRunnable, captureTimeout)
    }

    /** Capture a download URL and finish with RESULT_OK. */
    private fun capture(downloadUrl: String, webView: WebView?) {
        if (captured) return
        captured = true
        handler.removeCallbacks(timeoutRunnable)
        Log.i(TAG, "Captured: $downloadUrl")

        val referer = currentPageUrl ?: ""
        val ua = webView?.settings?.userAgentString ?: ""
        val cookie = try {
            CookieManager.getInstance().getCookie(downloadUrl) ?:
            CookieManager.getInstance().getCookie(currentPageUrl ?: downloadUrl)
        } catch (_: Exception) { "" }

        val result = Intent()
            .putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            .putExtra(EXTRA_REFERER, referer)
            .putExtra(EXTRA_USER_AGENT, ua)
            .putExtra(EXTRA_COOKIE, cookie)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** Called on cancel or timeout — finish without capture. */
    private fun finishCapturing(webView: WebView?) {
        if (captured) return
        captured = true
        handler.removeCallbacks(timeoutRunnable)
        Log.i(TAG, "Finishing without capture (cancel/timeout)")
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun finish() {
        if (!captured) {
            // Guard: if nothing captured yet, treat as cancelled
            handler.removeCallbacks(timeoutRunnable)
            Log.i(TAG, "Finishing without capture, result=CANCELED")
            setResult(Activity.RESULT_CANCELED)
        }
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        webView?.apply {
            stopLoading()
            onPause()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    /** Parse JS evaluation result: strip quotes, handle "null", blank. */
    private fun cleanJsResult(raw: String?): String? {
        return raw?.trim('"')?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Strict check for a real downloadable file URL: APK-family extension on the path
     * (query/fragment stripped) or a known file-CDN host. Page URLs like
     * `.../download/apk` and API endpoints must NOT pass this check.
     */
    private fun isDownloadUrl(u: String): Boolean {
        val lower = u.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".apk") || path.endsWith(".apkm") || path.endsWith(".xapk") ||
                path.endsWith(".apks") || path.endsWith(".aab") ||
                lower.contains("cloudflarestorage.com") ||
                lower.contains("d.apkpure.com") ||
                lower.contains("downloadr")
    }
}
