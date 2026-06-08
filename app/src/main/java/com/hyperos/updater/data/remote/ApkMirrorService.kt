package com.hyperos.updater.data.remote

import android.util.Log
import com.hyperos.updater.data.remote.dto.ApkMirrorRssItem
import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

data class ApkMirrorSearchItem(
    val appName: String,
    val version: String?,
    val devName: String,
    val pageUrl: String,
    val iconUrl: String?
)

@Singleton
class ApkMirrorService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun searchByName(query: String): List<ApkMirrorSearchItem> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=$encoded&bundles%5B%5D=apkm_bundles&bundles%5B%5D=apk_files"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkUtils.APKMIRROR_USER_AGENT)
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val html = response.body?.string() ?: return@withContext emptyList()
                val doc = Jsoup.parse(html)

                val results = mutableListOf<ApkMirrorSearchItem>()
                val seenSlugs = mutableSetOf<String>()
                val queryLower = query.lowercase()

                doc.select(".appRow").forEach { row ->
                    val h5 = row.select("h5.appRowTitle").firstOrNull() ?: return@forEach
                    val fullTitle = h5.attr("title").trim()
                    val link = h5.select("a.fontBlack, a[href*=/apk/]").firstOrNull() ?: return@forEach
                    val pageUrl = link.attr("href").let {
                        if (it.startsWith("http")) it else "https://www.apkmirror.com$it"
                    }

                    val text = link.text().trim()
                    if (!fullTitle.lowercase().contains(queryLower) &&
                        !text.lowercase().contains(queryLower)) return@forEach

                    // Extract slug: /apk/dev/app-slug/
                    val slugMatch = Regex("/apk/([^/]+/[^/]+)/").find(pageUrl)
                    val slug = slugMatch?.groupValues?.get(1) ?: return@forEach
                    if (slug in seenSlugs) return@forEach
                    seenSlugs.add(slug)

                    val version = Regex("(\\d+\\.\\d+(\\.\\d+)*)").find(fullTitle)?.value
                    val cleanName = fullTitle
                        .replace(Regex("\\s*\\d+\\.\\d+.*"), "")
                        .replace(Regex("\\s*\\(.*?\\)\\s*"), " ")
                        .trim()

                    val devEl = row.select(".byDeveloper a, .developer a").firstOrNull()
                    val devName = devEl?.text()?.trim() ?: slug.split("/").first()

                    results.add(ApkMirrorSearchItem(cleanName, version, devName, pageUrl, null))
                }

                val final = results.take(15)
                Log.d("ApkMirror", "search '$query': returning ${final.size} results")
                final
            } catch (e: Exception) {
                Log.w("ApkMirror", "Search failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun fetchAppFeed(slug: String): List<ApkMirrorRssItem> = withContext(Dispatchers.IO) {
        val url = "https://www.apkmirror.com/apk/$slug/feed/"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkUtils.APKMIRROR_USER_AGENT)
            .build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        parseRssFeed(body)
    }

    suspend fun scrapeDownloadUrl(releasePageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(releasePageUrl)
                .header("User-Agent", NetworkUtils.APKMIRROR_USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val doc = Jsoup.parse(html)

            doc.select("a[rel=nofollow]").firstOrNull { link ->
                link.attr("href").contains("download")
            }?.attr("href")?.let { href ->
                "https://www.apkmirror.com$href"
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRssFeed(xml: String): List<ApkMirrorRssItem> {
        val items = mutableListOf<ApkMirrorRssItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""
            var title = ""
            var link = ""
            var pubDate = ""
            var insideItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") insideItem = true
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem) {
                            when (currentTag) {
                                "title" -> title = parser.text
                                "link" -> link = parser.text
                                "pubDate" -> pubDate = parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items.add(ApkMirrorRssItem(title, link, pubDate))
                            }
                            insideItem = false
                            title = ""
                            link = ""
                            pubDate = ""
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) { }
        return items
    }
}
