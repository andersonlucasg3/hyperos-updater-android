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

data class AptoideResult(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?
)

data class AptoideSearchItem(
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val iconUrl: String?,
    val downloadUrl: String?
)

/**
 * Aptoide public API v7.
 *
 * Version check:  GET https://ws75.aptoide.com/api/7/getApp?package_name=<pkg>
 *   JSON path: nodes.meta.data → { name, file: { vername, vercode, path, md5sum, filesize } }
 *   "path" is a direct APK download URL → no WebView needed.
 *
 * Search:  GET https://ws75.aptoide.com/api/7/apps/search?query=<q>&limit=25
 *   JSON path: datalist.list[] → { package, name, file: { vername, vercode }, icon }
 */
@Singleton
class AptoideService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val BASE = "https://ws75.aptoide.com/api/7"
    }

    /** Check latest version by package name. Returns null when not found or on error. */
    suspend fun checkVersion(packageName: String): AptoideResult? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/getApp?package_name=$packageName"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }
            val json = JSONObject(body)

            val data = json.optJSONObject("nodes")
                ?.optJSONObject("meta")
                ?.optJSONObject("data")
                ?: return@withContext null

            val name = data.optString("name", "")
            val file = data.optJSONObject("file") ?: return@withContext null
            val versionName = file.optString("vername", "")
            val versionCode = file.optLong("vercode", 0L)
            val downloadUrl = file.optString("path", "").ifBlank { null }
            val size = file.optLong("filesize", 0L).takeIf { it > 0 }

            if (versionName.isNotEmpty() && versionCode > 0) {
                Log.i("Aptoide", "v$versionName ($versionCode) for $packageName")
                AptoideResult(
                    appName = name.ifBlank { packageName },
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = downloadUrl,
                    fileSize = size
                )
            } else null
        } catch (e: Exception) {
            Log.d("Aptoide", "checkVersion error for $packageName: ${e.message}")
            null
        }
    }

    /** Search apps by name. */
    suspend fun searchByName(query: String): List<AptoideSearchItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE/apps/search?query=$encoded&limit=25"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                response.body?.string() ?: return@withContext emptyList()
            }
            val json = JSONObject(body)

            val list = json.optJSONObject("datalist")
                ?.optJSONArray("list")
                ?: return@withContext emptyList()

            val results = mutableListOf<AptoideSearchItem>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val pkg = item.optString("package", "")
                val name = item.optString("name", "")
                val file = item.optJSONObject("file")
                val versionName = file?.optString("vername", "")
                val versionCode = file?.optLong("vercode", 0L) ?: 0L
                val icon = item.optString("icon", "").ifBlank { null }
                val dlUrl = file?.optString("path", "")?.ifBlank { null }

                if (name.isNotBlank() && pkg.isNotBlank()) {
                    results.add(
                        AptoideSearchItem(
                            appName = name,
                            packageName = pkg,
                            versionName = versionName?.ifBlank { null },
                            versionCode = versionCode,
                            iconUrl = icon,
                            downloadUrl = dlUrl
                        )
                    )
                }
            }
            Log.d("Aptoide", "search '$query': ${results.size} results")
            results
        } catch (e: Exception) {
            Log.d("Aptoide", "searchByName error: ${e.message}")
            emptyList()
        }
    }
}
