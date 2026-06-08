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
    suspend fun checkUpdate(codename: String, currentVersion: String): MemeOsOtaResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://memeosupdates.com/hyperos/$codename/"
            val request = Request.Builder().url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Extract region suffix from installed version (e.g. VMXMIXM from OS2.0.206.0.VMXMIXM)
            val currentSuffix = Regex("""\.([A-Z]{4,8})$""").find(currentVersion)?.groupValues?.get(1) ?: ""

            // Find all versions: full string like OS2.0.209.0.VMXEUXM
            val fullVersionRegex = Regex("""OS(\d+\.\d+\.\d+\.\d+\.[A-Z]+)""")
            val allVersions = fullVersionRegex.findAll(html).map { it.groupValues[1] }.toList()

            if (allVersions.isEmpty()) {
                Log.d("MemeOsOta", "No versions found for $codename")
                return@withContext null
            }

            // Prefer version matching device's region suffix
            val fullVersion = if (currentSuffix.isNotBlank()) {
                allVersions.firstOrNull { it.endsWith(currentSuffix) } ?: allVersions.first()
            } else {
                allVersions.first()
            }

            val versionUrl = "https://memeosupdates.com/hyperos/$codename/version/OS$fullVersion"
            val shortVersion = "OS${fullVersion.substringBeforeLast(".")}"
            Log.i("MemeOsOta", "Latest $shortVersion (full: OS$fullVersion, suffix: $currentSuffix) for $codename → $versionUrl")
            MemeOsOtaResult(shortVersion, versionUrl)
        } catch (e: Exception) {
            Log.d("MemeOsOta", "Error for $codename: ${e.message}")
            null
        }
    }
}
