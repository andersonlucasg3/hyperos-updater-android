package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class MemeOsResult(
    val appName: String,
    val versionName: String,
    val downloadUrl: String
)

@Singleton
class MemeOsService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun checkVersion(packageName: String): MemeOsResult? = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://memeosupdates.com/?s=$packageName"
            val request = Request.Builder().url(searchUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Check if the app was found (no "No results" text)
            if (html.contains("No results")) {
                Log.d("MemeOs", "No results for $packageName")
                return@withContext null
            }

            // Try to extract version from page title or content
            val version = extractVersion(html) ?: return@withContext null

            // Extract app name from search result link text
            val appName = Regex("""/apps/$packageName["'][^>]*>([^<]+)</a>""")
                .find(html)?.groupValues?.get(1)?.trim()
                ?: packageName

            val downloadUrl = "https://memeosupdates.com/apps/$packageName"
            Log.i("MemeOs", "v$version for $packageName")
            MemeOsResult(appName, version, downloadUrl)
        } catch (e: Exception) {
            Log.d("MemeOs", "Error for $packageName: ${e.message}")
            null
        }
    }

    /** Search by app name using memeosupdates.com/search/{query}, then fetch detail page for version. */
    suspend fun searchByName(query: String): MemeOsResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val searchUrl = "https://memeosupdates.com/search/$encoded"
            val request = Request.Builder().url(searchUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Extract first valid app link: href="/apps/{pkg}" (skip JS template placeholders like ${...})
            val linkRegex = Regex("""/apps/([a-zA-Z][^"'{}\s]+)""")
            val matches = linkRegex.findAll(html).toList()
            if (matches.isEmpty()) {
                Log.d("MemeOs", "No results for '$query' (html=${html.length} bytes)")
                return@withContext null
            }
            Log.d("MemeOs", "Found ${matches.size} app links for '$query'")

            // Use the first non-template match
            val pkg = matches.firstOrNull { !it.value.contains("{") }?.groupValues?.get(1) ?: run {
                Log.d("MemeOs", "All results are templates for '$query'")
                return@withContext null
            }
            var appName = query
            val downloadUrl = "https://memeosupdates.com/apps/$pkg"

            // Fetch the app detail page to get version and proper name
            val detailRequest = Request.Builder().url(downloadUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val detailResponse = okHttpClient.newCall(detailRequest).execute()
            val detailHtml = detailResponse.body?.string() ?: html

            // Extract version from detail page
            val version = extractVersion(detailHtml) ?: extractVersion(html)
            // Try to get better app name from detail page title
            val titleRegex = Regex("""<title>([^<]+)APK Download""")
            val titleName = titleRegex.find(detailHtml)?.groupValues?.get(1)?.trim()
            if (titleName != null) appName = titleName

            Log.i("MemeOs", "searchByName: $appName ${if (version != null) "v$version" else ""} → $downloadUrl")
            MemeOsResult(appName, version ?: "", downloadUrl)
        } catch (e: Exception) {
            Log.d("MemeOs", "searchByName error: ${e.message}")
            null
        }
    }

    private fun extractVersion(html: String): String? {
        // Format 1: <title>...RELEASE-6.5.000100.0...</title>
        Regex("RELEASE-([0-9]+(?:\\.[0-9]+)+)").find(html)?.groupValues?.get(1)?.let {
            val version = it.removePrefix("RELEASE-")
            if (version.isNotBlank()) return version
        }
        // Format 2: data-version attribute
        Regex("""data-version=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { return it }
        // Format 3: RELEASE- in text content
        return null
    }
}
