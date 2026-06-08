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

@Singleton
class FDroidService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun checkVersion(packageName: String): FDroidResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://f-droid.org/api/v1/packages/$packageName"
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
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
        } catch (e: Exception) {
            null
        }
    }
}
