package com.hyperos.updater.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class DownloadActivity : Activity() {

    private var captured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        Log.i("DownloadActivity", "Loading: $url")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Status bar
        val statusBar = TextView(this).apply {
            text = "Tap 'Download APK' button below"
            setPadding(32, 16, 32, 8)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1a73e8"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        container.addView(statusBar)

        // Progress bar
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        container.addView(progressBar)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 16; Xiaomi 17 Pro Max) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            settings.setSupportMultipleWindows(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )

            setDownloadListener { downloadUrl, _, _, _, _ ->
                Log.i("DownloadActivity", "DownloadListener: $downloadUrl")
                capture(downloadUrl)
            }

            // Evaluate JS to intercept XHR/fetch download API calls (not window.location)
            evaluateJavascript(
                "(function(){" +
                "var origFetch=window.fetch;" +
                "window.fetch=function(url,opts){" +
                "if(typeof url==='string'&&(url.indexOf('downloadr')>=0||url.indexOf('.apk')>=0)){" +
                "window._apkm_dl_url=url;" +
                "}" +
                "return origFetch.apply(this,arguments);" +
                "};" +
                "var origOpen=XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open=function(method,url){" +
                "this._url=url;" +
                "return origOpen.apply(this,arguments);" +
                "};" +
                "var origSend=XMLHttpRequest.prototype.send;" +
                "XMLHttpRequest.prototype.send=function(body){" +
                "var self=this;" +
                "self.addEventListener('load',function(){" +
                "try{" +
                "if(self._url&&(self._url.indexOf('downloadr')>=0||self._url.indexOf('.apk')>=0||self._url.indexOf('cdn')>=0)){" +
                "window._apkm_dl_url=self.responseText||self._url;" +
                "}" +
                "var data=JSON.parse(self.responseText||'{}');" +
                "var u=data.url||data.download_url||data.link;" +
                "if(typeof u==='string'&&u.length>10&&(u.indexOf('downloadr')>=0||u.indexOf('.apk')>=0)){" +
                "window._apkm_dl_url=u;" +
                "}" +
                "}catch(e){}" +
                "});" +
                "return origSend.apply(this,arguments);" +
                "};" +
                "})()", null
            )

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 80) progressBar.visibility = android.view.View.GONE
                }
            }

            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    // Check for intercepted JS download URL after any navigation
                    view?.evaluateJavascript("window._apkm_dl_url||''") { result ->
                        val clean = result?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                        if (clean != null && clean != "") {
                            Log.i("DownloadActivity", "JS captured URL: $clean")
                            capture(clean)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    Log.i("DownloadActivity", "Page loaded: $finishedUrl")
                    statusBar.text = "Tap 'Download APK' to start"
                    // Check for intercepted JS download URL after page load
                    view?.evaluateJavascript("window._apkm_dl_url||''") { result ->
                        val clean = result?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                        if (clean != null && clean != "") {
                            Log.i("DownloadActivity", "JS captured after load: $clean")
                            capture(clean)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, requestUrl: String?): Boolean {
                    Log.i("DownloadActivity", "Navigate: $requestUrl")
                    if (requestUrl == null) return false
                    // Only capture final APK file URLs and known-good CDNs (Cloudflare R2)
                    val isApkUrl = requestUrl.endsWith(".apk") || requestUrl.endsWith(".xapk") ||
                            requestUrl.endsWith(".apks") || requestUrl.endsWith(".apkm") ||
                            requestUrl.endsWith(".aab")
                    val isR2 = requestUrl.contains("cloudflarestorage.com")
                    if ((isApkUrl || isR2) && requestUrl != url) {
                        Log.i("DownloadActivity", "CDN: $requestUrl")
                        capture(requestUrl)
                        return true
                    }
                    return false
                }
            }
        }
        container.addView(webView)
        setContentView(container)
        webView.loadUrl(url)
    }

    private fun capture(downloadUrl: String) {
        if (captured) return
        captured = true
        Log.i("DownloadActivity", "Captured: $downloadUrl")
        val result = Intent().putExtra("downloadUrl", downloadUrl)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun finish() {
        if (!captured) {
            Log.i("DownloadActivity", "Finishing without capture, result=CANCELED")
            setResult(Activity.RESULT_CANCELED)
        }
        super.finish()
    }
}
