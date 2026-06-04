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

data class ApkPureResult(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?
)

@Singleton
class ApkPureService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    data class VersionResult(
        val versionName: String,
        val downloadUrl: String
    )

    suspend fun checkVersion(packageName: String): VersionResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://d.apkpure.com/b/APK/$packageName?version=latest"
            // Build a client that doesn't follow redirects to capture the 302 Location header
            val noRedirectClient = okHttpClient.newBuilder().followRedirects(false).build()
            val request = Request.Builder().url(url).head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Redmi 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://apkpure.com/")
                .header("Origin", "https://apkpure.com")
                .build()
            val response = noRedirectClient.newCall(request).execute()
            // Parse version from 302 Location header's filename parameter
            val location = response.header("Location", "") ?: response.header("location", "") ?: ""
            val filenameParam = Regex("""filename=([^&]+)""").find(location)?.groupValues?.get(1) ?: ""
            val decoded = java.net.URLDecoder.decode(filenameParam, "UTF-8")
            val version = Regex("""(\d+\.\d+(?:\.\d+)*)""").find(decoded)?.value
            response.close()
            if (version != null) {
                Log.i("ApkPure", "v$version for $packageName")
                // Build download URL from original request
                VersionResult(version, url)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(packageName: String): ApkPureResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://apkpure.com/search?q=$packageName"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val doc = Jsoup.parse(html)

            val firstResult = doc.select(".search-results .apk").firstOrNull() ?: return@withContext null
            val appName = firstResult.select(".title").firstOrNull()?.text() ?: return@withContext null
            val versionText = firstResult.select(".version").firstOrNull()?.text() ?: ""
            val versionCode = versionText.filter { it.isDigit() }.toLongOrNull() ?: 0L

            val detailUrl = firstResult.select("a[href]").firstOrNull()?.attr("href")
            val downloadUrl = if (detailUrl != null) {
                "https://apkpure.com$detailUrl"
            } else null

            ApkPureResult(appName, versionText, versionCode, downloadUrl, null)
        } catch (e: Exception) {
            null
        }
    }

    data class SearchItem(
        val appName: String,
        val packageName: String,
        val detailUrl: String
    )

    suspend fun searchByName(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        try {
            val url = "https://apkpure.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            val doc = Jsoup.parse(html)

            doc.select(".search-results .apk, .apk-list .apk-item, .list .item, .first").mapNotNull { el ->
                val link = el.select("a[href]").firstOrNull()?.attr("href") ?: return@mapNotNull null
                val name = el.select(".title, .name, h2, h3").firstOrNull()?.text()
                    ?: el.select("a").firstOrNull()?.text()
                    ?: return@mapNotNull null
                val pkg = link.removePrefix("/").split("/").lastOrNull()
                    ?.removeSuffix(".html")
                    ?: ""

                SearchItem(appName = name, packageName = pkg, detailUrl = "https://apkpure.com$link")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun scrapeDownloadUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val doc = Jsoup.parse(html)

            doc.select("a[href*=download]").firstOrNull()?.attr("href")
        } catch (e: Exception) {
            null
        }
    }
}
