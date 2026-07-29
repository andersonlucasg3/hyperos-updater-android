package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class TencentResult(
    val appName: String,
    val versionName: String,
    val downloadUrl: String?
)

/**
 * Tencent MyApp (应用宝) version check.
 *
 * Uses the public simple.jsp endpoint which returns an HTML page containing
 * `window.systemData = {...}` with app details. The domain only resolves from
 * Chinese networks — any error (DNS, HTTP, parse) returns null.
 */
@Singleton
class TencentService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun checkVersion(packageName: String): TencentResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://a.app.sj.qq.com/o/simple.jsp?pkgname=$packageName"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val html = response.body?.string() ?: return@withContext null

            // Extract window.systemData = {...}; from the HTML
            val json = extractSystemData(html) ?: return@withContext null
            val appDetail = json.optJSONObject("appDetail") ?: return@withContext null

            val versionName = appDetail.optString("versionName", "").takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val appName = appDetail.optString("appName", "")
            // Prefer 64-bit APK URL over 32-bit
            val downloadUrl = appDetail.optString("apkUrl64", "").takeIf { it.isNotEmpty() }
                ?: appDetail.optString("apkUrl", "").takeIf { it.isNotEmpty() }

            Log.i("Tencent", "v$versionName for $packageName ($appName)")
            TencentResult(appName = appName, versionName = versionName, downloadUrl = downloadUrl)
        } catch (e: Exception) {
            Log.d("Tencent", "checkVersion error for $packageName: ${e.message}")
            null
        }
    }

    /**
     * Find `window.systemData = {...};` in the HTML and extract the JSON object.
     * Handles optional whitespace, semicolons, and line breaks around the assignment.
     */
    private fun extractSystemData(html: String): JSONObject? {
        try {
            val prefix = "window.systemData"
            val startIdx = html.indexOf(prefix)
            if (startIdx < 0) return null

            // Find the opening brace after the '='
            val eqIdx = html.indexOf('=', startIdx + prefix.length)
            if (eqIdx < 0) return null

            val braceIdx = html.indexOf('{', eqIdx + 1)
            if (braceIdx < 0) return null

            // Walk balanced braces to find the closing brace
            var depth = 0
            val len = html.length
            for (i in braceIdx until len) {
                when (html[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val jsonStr = html.substring(braceIdx, i + 1)
                            return JSONObject(jsonStr)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("Tencent", "extractSystemData parse error: ${e.message}")
        }
        return null
    }
}
