package com.hyperos.updater.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class WebViewDownloadResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var capturedUrl: String? = null
    private var clicked = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveDownloadUrl(pageUrl: String): String? = suspendCancellableCoroutine { continuation ->
        capturedUrl = null
        var timedOut = false

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            timedOut = true
            if (continuation.isActive) continuation.resume(null)
        }
        handler.postDelayed(timeoutRunnable, 15_000)

        handler.post {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Intercept download triggers
                setDownloadListener { url, _, _, _, _ ->
                    Log.d("WebViewDL", "Download triggered: $url")
                    if (!timedOut) {
                        capturedUrl = url
                        handler.removeCallbacks(timeoutRunnable)
                        destroy()
                        if (continuation.isActive) continuation.resume(url)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url != null && url != pageUrl && url != "https://www.apkmirror.com/" &&
                            (url.contains("downloadr") || url.endsWith(".apk") ||
                             url.contains(".apk?") || url.contains("cdn") ||
                             url.contains("apk-download"))) {
                            Log.d("WebViewDL", "Intercepted CDN URL: $url")
                            capturedUrl = url
                            handler.removeCallbacks(timeoutRunnable)
                            view?.destroy()
                            if (continuation.isActive) continuation.resume(url)
                            return true
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (url.contains("downloadr") && url != pageUrl) {
                            Log.d("WebViewDL", "Intercepted AJAX/CDN request: $url")
                            capturedUrl = url
                            handler.removeCallbacks(timeoutRunnable)
                            view?.post { view?.destroy() }
                            if (continuation.isActive) continuation.resume(url)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!timedOut && capturedUrl == null && !clicked) {
                            clicked = true
                            // Inject JS to intercept XHR and find download button
                            view?.evaluateJavascript(
                                "(function(){" +
                                // Override XHR to capture download URL responses
                                "var origOpen=XMLHttpRequest.prototype.open;" +
                                "XMLHttpRequest.prototype.open=function(method,url){" +
                                "this._url=url;" +
                                "return origOpen.apply(this,arguments);" +
                                "};" +
                                "var origSend=XMLHttpRequest.prototype.send;" +
                                "XMLHttpRequest.prototype.send=function(body){" +
                                "var xhr=this;" +
                                "xhr.addEventListener('load',function(){" +
                                "if(xhr._url&&xhr._url.indexOf('download')>=0){" +
                                "console.log('APKM_DL_URL:'+xhr.responseText);" +
                                "}});" +
                                "return origSend.apply(this,arguments);" +
                                "};" +
                                // Click the download button
                                "var btn=document.querySelector('.downloadButton, a[href*=\"-android-apk-download\"], #downloadButton, .apk_download_button');" +
                                "if(btn){btn.click();return 'clicked';}" +
                                // Try to find any element that triggers download
                                "var all=document.querySelectorAll('[onclick]');" +
                                "for(var i=0;i<all.length;i++){if(all[i].outerHTML.indexOf('download')>=0){all[i].click();return 'clicked_onclick';}}" +
                                "return 'not_found';" +
                                "})()"
                            ) { result ->
                                Log.d("WebViewDL", "Click result: $result")
                            }
                        }
                    }
                }
            }
            webView.loadUrl(pageUrl)
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            handler.post {
                capturedUrl = null
            }
        }
    }
}
