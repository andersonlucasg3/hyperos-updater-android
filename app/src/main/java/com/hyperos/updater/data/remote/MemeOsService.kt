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

    /** Search by app name using memeosupdates.com/search/{query} */
    suspend fun searchByName(query: String): MemeOsResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val searchUrl = "https://memeosupdates.com/search/$encoded"
            val request = Request.Builder().url(searchUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            if (html.contains("No results")) {
                Log.d("MemeOs", "No results for '$query'")
                return@withContext null
            }

            // Extract first result: href="/apps/{pkg}" and nearby title text
            val linkRegex = Regex("""href="/apps/([^"]+)"[^>]*>""")
            val match = linkRegex.find(html) ?: return@withContext null
            val pkg = match.groupValues[1]

            // Try to get app name from a nearby element or URL
            val snippet = html.substring(maxOf(0, match.range.first - 200), minOf(match.range.first + 500, html.length))
            val nameRegex = Regex("""<a[^>]*href="/apps/$pkg"[^>]*>([^<]+)</a>""")
            val appName = nameRegex.find(snippet)?.groupValues?.get(1)?.trim() ?: query

            // Extract version from the snippet
            val version = extractVersion(snippet) ?: extractVersion(html)

            val downloadUrl = "https://memeosupdates.com/apps/$pkg"
            if (version != null) {
                Log.i("MemeOs", "searchByName: $appName v$version → $downloadUrl")
                MemeOsResult(appName, version, downloadUrl)
            } else {
                // Return result even without version (user can check page)
                Log.i("MemeOs", "searchByName: $appName (no version) → $downloadUrl")
                MemeOsResult(appName, "", downloadUrl)
            }
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
