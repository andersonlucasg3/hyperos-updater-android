package com.hyperos.updater.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class FDroidResult(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?
)

/** A single version entry from the F-Droid packages array. */
data class FDroidVersion(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String?
)

@Singleton
class FDroidService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun checkVersion(packageName: String): FDroidResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://f-droid.org/api/v1/packages/$packageName"
            val request = Request.Builder().url(url).build()
            val result = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val versionName = json.optString("suggestedVersionName", "")
                val versionCode = json.optLong("suggestedVersionCode", 0L)

                // Build download URL from the packages array
                val packages = json.optJSONArray("packages") ?: return@withContext null
                var downloadUrl: String? = null
                for (i in 0 until packages.length()) {
                    val pkg = packages.getJSONObject(i)
                    if (pkg.optLong("versionCode") == versionCode) {
                        val apkName = pkg.optString("apkName", "")
                        if (apkName.isNotEmpty()) {
                            downloadUrl = "https://f-droid.org/repo/$apkName"
                            break
                        }
                    }
                }

                if (versionCode > 0 && versionName.isNotEmpty()) {
                    Log.i("FDroid", "v$versionName for $packageName")
                    FDroidResult(versionName, versionCode, downloadUrl)
                } else null
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** Returns all versions from the F-Droid packages payload for [packageName]. */
    suspend fun getVersionHistory(packageName: String): List<FDroidVersion> = withContext(Dispatchers.IO) {
        try {
            val url = "https://f-droid.org/api/v1/packages/$packageName"
            val request = Request.Builder().url(url).build()
            val result = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)

                val packages = json.optJSONArray("packages")
                    ?: json.optJSONObject("packages")?.let { obj ->
                        val arr = org.json.JSONArray()
                        obj.keys().forEach { key -> arr.put(obj.get(key)) }
                        arr
                    }
                    ?: return@withContext emptyList()

                val list = mutableListOf<FDroidVersion>()
                for (i in 0 until packages.length()) {
                    val pkg = packages.getJSONObject(i)
                    val vn = pkg.optString("versionName", "")
                    val vc = pkg.optLong("versionCode", 0L)
                    val apkName = pkg.optString("apkName", "")
                    val apkUrl = if (apkName.isNotEmpty()) "https://f-droid.org/repo/$apkName" else null
                    if (vn.isNotEmpty() && vc > 0) {
                        list.add(FDroidVersion(vn, vc, apkUrl))
                    }
                }
                list.sortedByDescending { it.versionCode }
            }
            result
        } catch (e: Exception) {
            Log.d("FDroid", "getVersionHistory error for $packageName: ${e.message}")
            emptyList()
        }
    }
}
