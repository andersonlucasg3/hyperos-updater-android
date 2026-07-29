package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

data class UptodownResult(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val pageUrl: String
)

data class UptodownSearchItem(
    val appName: String,
    val versionName: String?,
    val pageUrl: String,
    val iconUrl: String?
)

/**
 * Uptodown best-effort scraper.
 *
 * IMPORTANT: There is NO reliable package-name → Uptodown-URL mapping.
 * App pages live at https://<app-slug>.en.uptodown.com/android where the slug
 * is an arbitrary human-readable name (e.g. "whatsapp", "spotify").
 *
 * Strategy:
 *   - searchByName(query):  scrape https://www.uptodown.com/android/search/<query>
 *   - checkVersion(packageName):  search with the package name (last segment after dots)
 *     as the query, take the first result, then scrape that app page for version info.
 *     This is inherently unreliable and may return null for many packages.
 *
 * This source is explicitly "if possible" — failures return null/empty and never
 * affect the aggregate check or build.
 */
@Singleton
class UptodownService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val SEARCH_BASE = "https://www.uptodown.com/android/search"
    }

    /**
     * Best-effort version check by package name.
     * Uses the last segment of the package name as a search query, takes the first
     * search result, and scrapes its app page for version info.
     * Returns null when anything fails (no results, parse error, network error).
     */
    suspend fun checkVersion(packageName: String): UptodownResult? = withContext(Dispatchers.IO) {
        try {
            // Use the last segment of the package name as the most likely search term
            val searchTerm = packageName.substringAfterLast(".").ifBlank { packageName }
            val results = rawSearch(searchTerm)
            val first = results.firstOrNull() ?: return@withContext null
            val detail = scrapeAppPage(first.pageUrl) ?: return@withContext null

            Log.i("Uptodown", "v${detail.versionName} for $packageName (via search '$searchTerm')")
            UptodownResult(
                appName = first.appName,
                versionName = detail.versionName,
                versionCode = detail.versionCode,
                downloadUrl = detail.downloadUrl,
                pageUrl = first.pageUrl
            )
        } catch (e: Exception) {
            Log.d("Uptodown", "checkVersion error for $packageName: ${e.message}")
            null
        }
    }

    /** Search apps by name. Returns empty list on any failure. */
    suspend fun searchByName(query: String): List<UptodownSearchItem> = withContext(Dispatchers.IO) {
        try {
            rawSearch(query)
        } catch (e: Exception) {
            Log.d("Uptodown", "searchByName error: ${e.message}")
            emptyList()
        }
    }

    // --- internal ---

    private fun rawSearch(query: String): List<UptodownSearchItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$SEARCH_BASE/$encoded"
        val request = Request.Builder().url(url)
            .header("User-Agent", NetworkUtils.USER_AGENT)
            .build()
        val html = okHttpClient.newCall(request).execute().use { response ->
            response.body?.string() ?: return emptyList()
        }
        val doc = Jsoup.parse(html)

        val results = mutableListOf<UptodownSearchItem>()

        // Try multiple selector strategies — Uptodown may change their markup
        // Strategy A: card-based listing
        doc.select(".card, .app-card, article, .item, .search-result, .list-item").forEach { el ->
            val link = el.select("a[href*=/android]").firstOrNull()
                ?: el.select("a[href]").firstOrNull()
                ?: return@forEach
            val href = link.attr("href")
            val pageUrl = if (href.startsWith("http")) href
            else if (href.startsWith("/")) "https://www.uptodown.com$href"
            else return@forEach

            // Only keep android app pages
            if (!pageUrl.contains("/android")) return@forEach

            val name = link.select("h2, h3, h4, .name, .title, [class*=name], [class*=title]")
                .firstOrNull()?.text()
                ?: link.text().trim()
                ?: return@forEach

            val version = el.select(".version, [class*=version], .app-version, .latest-version")
                .firstOrNull()?.text()?.replace(Regex("(?i)version[:\\s]*"), "")?.trim()

            val icon = el.select("img[src]").firstOrNull()?.attr("src")

            results.add(UptodownSearchItem(name, version, pageUrl, icon))
        }

        // Strategy B: if card selectors found nothing, try generic link extraction
        if (results.isEmpty()) {
            doc.select("a[href*=/android]").forEach { link ->
                val href = link.attr("href")
                val pageUrl = if (href.startsWith("http")) href
                else if (href.startsWith("/")) "https://www.uptodown.com$href"
                else return@forEach
                val name = link.select("h2, h3, h4, .name, .title").firstOrNull()?.text()
                    ?: link.text().trim()
                if (name.isNotBlank() && name.length > 2 && name.length < 80) {
                    val icon = link.select("img[src]").firstOrNull()?.attr("src")
                    results.add(UptodownSearchItem(name, null, pageUrl, icon))
                }
            }
        }

        Log.d("Uptodown", "search '$query': ${results.size} results (html=${html.length}B)")
        return results.distinctBy { it.pageUrl }
    }

    private data class AppPageDetail(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String?
    )

    private fun scrapeAppPage(pageUrl: String): AppPageDetail? {
        return try {
            val request = Request.Builder().url(pageUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val html = okHttpClient.newCall(request).execute().use { response ->
                response.body?.string() ?: return null
            }
            val doc = Jsoup.parse(html)

            // Version: look for common Uptodown version markers
            // Often in: <span class="version">X.Y.Z</span>, JSON-LD, or meta tags
            var versionName: String? = null
            var versionCode: Long = 0L

            // Try JSON-LD structured data first (most reliable if present)
            doc.select("script[type=application/ld+json]").forEach { script ->
                val json = script.data()
                // Extract version from JSON-LD: "softwareVersion": "X.Y.Z"
                val v = Regex(""""softwareVersion"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                if (v != null) versionName = v
            }

            // Fallback: look for version text in common containers
            if (versionName == null) {
                versionName = doc.select(".version, [class*=version], #version, .app-version, " +
                        ".latest-version, [itemprop=softwareVersion], [data-version]")
                    .firstOrNull()?.let { el ->
                        el.attr("content").ifBlank { el.text().trim() }
                    }
            }

            // Fallback: try meta tags
            if (versionName == null) {
                versionName = doc.select("meta[itemprop=version], meta[name=version]")
                    .firstOrNull()?.attr("content")
            }

            // Fallback: regex on full HTML for version patterns near app context
            if (versionName == null) {
                // Look for "Version X.Y.Z" patterns
                versionName = Regex("""(?:Version|v\.?)\s*(\d+\.\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)
            }

            if (versionName.isNullOrBlank()) return null

            // Try to extract version code from JSON-LD or data attributes
            doc.select("script[type=application/ld+json]").forEach { script ->
                val json = script.data()
                val vc = Regex(""""version"\s*:\s*(\d+)"""").find(json)?.groupValues?.get(1)
                    ?.toLongOrNull()
                if (vc != null) versionCode = vc
            }

            // Extract download URL if available
            val downloadUrl = doc.select("a[href*=download], a[data-url*=download], .download a, " +
                    "#download-button, [class*=download] a[href]")
                .firstOrNull()?.attr("href")?.let { href ->
                    if (href.startsWith("http")) href
                    else if (href.startsWith("/")) "https://www.uptodown.com$href"
                    else null
                }

            AppPageDetail(versionName, versionCode, downloadUrl)
        } catch (e: Exception) {
            Log.d("Uptodown", "scrapeAppPage error for $pageUrl: ${e.message}")
            null
        }
    }
}
