package com.hyperos.updater.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SelfUpdateRelease(
    val version: String,
    val title: String?,
    val changelog: String?,
    val publishedAt: String?,
    val apkUrl: String?
)

@Singleton
class SelfUpdateService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SelfUpdate"
        private const val REPO = "andersonlucasg3/hyperos-updater-android"
    }

    suspend fun checkLatestRelease(): SelfUpdateRelease? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$REPO/releases/latest"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val result = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "GitHub releases/latest returned ${response.code} — no release yet?")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").trimStart('v', 'V')
                val name = json.optString("name", "").takeIf { it.isNotBlank() }
                val bodyText = json.optString("body", "").takeIf { it.isNotBlank() }
                    ?.let { if (it.length > 500) it.take(500) + "…" else it }
                val publishedAt = json.optString("published_at", "").takeIf { it.isNotBlank() }

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val dlUrl = asset.optString("browser_download_url", "")
                        val assetName = asset.optString("name", "")
                        if (dlUrl.endsWith(".apk") || assetName.endsWith(".apk")) {
                            apkUrl = dlUrl
                            break
                        }
                    }
                }

                Log.i(TAG, "Latest release: v$tagName, apkUrl=${apkUrl != null}")
                SelfUpdateRelease(
                    version = tagName,
                    title = name,
                    changelog = bodyText,
                    publishedAt = publishedAt,
                    apkUrl = apkUrl
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check self-update: ${e.message}")
            null
        }
    }
}
