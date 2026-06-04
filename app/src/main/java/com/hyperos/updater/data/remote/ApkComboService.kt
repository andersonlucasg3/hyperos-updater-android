package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class ApkComboResult(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?
)

@Singleton
class ApkComboService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun search(packageName: String): ApkComboResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://apkcombo.com/search/$packageName"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val finalUrl = response.request.url.toString()

            // Parse version from JSON-LD: "softwareVersion": "X.Y.Z"
            val version = Regex(""""softwareVersion"\s*:\s*"(\d+\.\d+(?:\.\d+)*)"""").find(html)?.groupValues?.get(1)
            // Also try app name
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: packageName

            if (version != null) {
                Log.i("ApkCombo", "v$version for $packageName")
                val versionCode = version.replace(".", "").take(8).toLongOrNull() ?: 0L
                ApkComboResult(name, version, versionCode, "$finalUrl/download/apk", null)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
