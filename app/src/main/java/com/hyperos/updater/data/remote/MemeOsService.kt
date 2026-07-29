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

data class MemeOsAppDetails(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val region: String?,
    val fileSizeBytes: Long?,
    val publishedDate: String?,
    val downloadUrl: String
)

@Singleton
class MemeOsService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    @Volatile
    private var catalogCache: Map<String, String>? = null

    /** Fetch the system apps catalog from memeosupdates.com/apps/ (packageName → appName). Cached per scan. */
    suspend fun fetchSystemAppsCatalog(forceRefresh: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        if (!forceRefresh) catalogCache?.let { return@withContext it }
        try {
            val request = Request.Builder()
                .url("https://memeosupdates.com/apps/")
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext (catalogCache ?: emptyMap())

            val regex = Regex(
                """<a href="/apps/([^"]+)" class="app-card list-card">.*?<h3 class="app-name">([^<]+)</h3>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val catalog = regex.findAll(html).associate { it.groupValues[1] to it.groupValues[2].trim() }
            if (catalog.isNotEmpty()) catalogCache = catalog
            Log.i("MemeOs", "Catalog: ${catalog.size} system apps")
            catalog.ifEmpty { catalogCache ?: emptyMap() }
        } catch (e: Exception) {
            Log.d("MemeOs", "Catalog error: ${e.message}")
            catalogCache ?: emptyMap()
        }
    }

    /** Fetch app detail page directly (no search). Picks the version with the highest versionCode across regions. */
    suspend fun getAppDetails(packageName: String): MemeOsAppDetails? = withContext(Dispatchers.IO) {
        try {
            val pageUrl = "https://memeosupdates.com/apps/$packageName"
            val request = Request.Builder().url(pageUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("MemeOs", "Detail HTTP ${response.code} for $packageName")
                return@withContext null
            }
            val html = response.body?.string() ?: return@withContext null

            val appName = Regex("""<h1 class="app-name-about">([^<]+)</h1>""")
                .find(html)?.groupValues?.get(1)?.trim() ?: packageName

            // Version history items: region, version, date, size, versionCode (from href)
            val itemRegex = Regex(
                """<div class="version-item" data-region="(\w+)">.*?version-number">([^<]+)<.*?version-date">([^<]+)<.*?version-size">([^<]+)<.*?href="/apps/[^"]+/(\d+)"""",
                RegexOption.DOT_MATCHES_ALL
            )
            val latest = itemRegex.findAll(html).maxByOrNull { it.groupValues[5].toLong() }

            if (latest != null) {
                val version = latest.groupValues[2].trim()
                val code = latest.groupValues[5].toLong()
                val region = latest.groupValues[1]
                val sizeBytes = parseSizeToBytes(latest.groupValues[4])
                val date = latest.groupValues[3].trim()
                val downloadUrl = "https://memeosupdates.com/apps/$packageName/$code"
                Log.i("MemeOs", "Detail $packageName: v$version (code=$code, $region, $date)")
                return@withContext MemeOsAppDetails(packageName, appName, version, code, region, sizeBytes, date, downloadUrl)
            }

            // Fallback: header + App Details grid (pages without version history)
            val version = Regex("""<span class="app-version">Version ([^<]+)</span>""")
                .find(html)?.groupValues?.get(1)?.trim() ?: return@withContext null
            val code = Regex("""Version Code</span>\s*<span class="detail-value">(\d+)</span>""")
                .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val sizeBytes = Regex("""Size</span>\s*<span class="detail-value">([^<]+)</span>""")
                .find(html)?.groupValues?.get(1)?.let { parseSizeToBytes(it) }
            val date = Regex("""Last Updated</span>\s*<span class="detail-value">([^<]+)</span>""")
                .find(html)?.groupValues?.get(1)?.trim()
            val downloadUrl = if (code > 0) "https://memeosupdates.com/apps/$packageName/$code" else pageUrl
            Log.i("MemeOs", "Detail $packageName (grid fallback): v$version (code=$code)")
            MemeOsAppDetails(packageName, appName, version, code, null, sizeBytes, date, downloadUrl)
        } catch (e: Exception) {
            Log.d("MemeOs", "Detail error for $packageName: ${e.message}")
            null
        }
    }

    private fun parseSizeToBytes(size: String): Long? {
        val m = Regex("""([\d.,]+)\s*(KB|MB|GB)""", RegexOption.IGNORE_CASE).find(size) ?: return null
        val value = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val multiplier = when (m.groupValues[2].uppercase()) {
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).toLong()
    }
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

    /**
     * Resolve the direct signed APK download URL from a version page URL, bypassing the
     * 20-second countdown entirely (two plain HTTP GETs, no WebView needed):
     *
     * 1. GET the version page → regex-extract `data-download-url` (dl=0 preferred, fallback dl=1).
     * 2. GET that URL with Referer set to the version page → regex-extract the signed
     *    `https://download.memeosupdates.com/…` URL.
     *
     * Returns null on any failure (network error, missing fields, signature expiry, etc.)
     * so callers can fall back to the WebView flow.
     */
    suspend fun resolveDirectDownloadUrl(versionPageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            // ── Step 1: get the download-started token URL from the version page ──
            val pageRequest = Request.Builder().url(versionPageUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT).build()
            val pageHtml = okHttpClient.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            // Prefer dl=0, fall back to dl=1
            val tokenUrl = Regex("""data-download-url="([^"]+download-started\?dl=0[^"]+)"""")
                .find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""data-download-url="([^"]+download-started\?dl=1[^"]+)"""")
                    .find(pageHtml)?.groupValues?.get(1)
                ?: return@withContext null

            val step2Url = tokenUrl.replace("&amp;", "&")
            Log.d("MemeOs", "Step 1 → $step2Url")

            // ── Step 2: GET the token URL with Referer → extract signed download URL ──
            val step2Request = Request.Builder().url(step2Url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .header("Referer", versionPageUrl)
                .build()
            val step2Html = okHttpClient.newCall(step2Request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val signedUrl = Regex("""https://download\.memeosupdates\.com/[^"'\s<>]+""")
                .find(step2Html)?.value
                ?.replace("&amp;", "&")

            if (signedUrl != null) {
                Log.i("MemeOs", "Resolved direct URL: ${signedUrl.take(80)}...")
            } else {
                Log.d("MemeOs", "Step 2: no signed URL found")
            }
            signedUrl
        } catch (e: Exception) {
            Log.d("MemeOs", "resolveDirectDownloadUrl error: ${e.message}")
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
