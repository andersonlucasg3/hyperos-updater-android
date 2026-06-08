package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class MemeOsOtaResult(
    val versionName: String,
    val downloadUrl: String
)

@Singleton
class MemeOsOtaService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun checkUpdate(codename: String): MemeOsOtaResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://memeosupdates.com/hyperos/$codename/"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Extract first OS version from embedded version strings like OS3.0.313.0.
            val versionRegex = Regex("OS(\\d+\\.\\d+\\.\\d+\\.\\d+)\\.")
            val version = versionRegex.find(html)?.groupValues?.get(1)
            if (version != null) {
                Log.i("MemeOsOta", "Latest OS$version for $codename")
                MemeOsOtaResult("OS$version", url)
            } else {
                Log.d("MemeOsOta", "No version found for $codename")
                null
            }
        } catch (e: Exception) {
            Log.d("MemeOsOta", "Error for $codename: ${e.message}")
            null
        }
    }
}
