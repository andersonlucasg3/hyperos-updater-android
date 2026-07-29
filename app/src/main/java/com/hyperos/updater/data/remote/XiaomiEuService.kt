package com.hyperos.updater.data.remote

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

data class XiaomiEuRom(
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val md5: String?,
    val publishedDate: String?
)

@Singleton
class XiaomiEuService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "XiaomiEu"
        private const val RSS_URL =
            "https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/"
        private val VERSION_REGEX = Regex("""_OS(\d+)\.(\d+)\.(\d+)\.(\d+)_""")
    }

    suspend fun checkLatestRom(codename: String): XiaomiEuRom? = withContext(Dispatchers.IO) {
        try {
            val codenameUpper = codename.uppercase()
            val request = Request.Builder().url(RSS_URL).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "RSS feed returned HTTP ${response.code}")
                return@withContext null
            }
            val body = response.body?.string() ?: return@withContext null

            val items = parseRssItems(body)

            // Filter: first item whose title contains _{CODENAME_UPPERCASE}_
            val match = items.firstOrNull { item ->
                item.title.contains("_${codenameUpper}_")
            } ?: run {
                Log.i(TAG, "No xiaomi.eu ROM found for codename $codenameUpper in ${items.size} items")
                return@withContext null
            }

            // Extract numeric version from filename: _OS{major}.{minor}.{patch}.{build}_
            val versionMatch = VERSION_REGEX.find(match.title)
            val version = versionMatch?.let {
                val (major, minor, patch, build) = it.destructured
                "OS$major.$minor.$patch.$build"
            } ?: match.title

            Log.i(TAG, "Latest ROM for $codename: $version — ${match.title} (${match.fileSize} bytes)")

            XiaomiEuRom(
                version = version,
                fileName = match.title,
                downloadUrl = match.link,
                sizeBytes = match.fileSize,
                md5 = match.md5,
                publishedDate = match.pubDate
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check xiaomi.eu ROM for $codename: ${e.message}")
            null
        }
    }

    // ── RSS XML parsing ──────────────────────────────────────────────

    private data class RssItem(
        val title: String,
        val link: String,
        val pubDate: String?,
        val fileSize: Long,
        val md5: String?
    )

    private fun parseRssItems(xml: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inItem = false
            var currentTitle = ""
            var currentLink = ""
            var currentPubDate: String? = null
            var currentFileSize = 0L
            var currentMd5: String? = null
            var pendingMd5 = false
            val textBuffer = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        textBuffer.clear()
                        when (parser.name) {
                            "item" -> {
                                inItem = true
                                currentTitle = ""
                                currentLink = ""
                                currentPubDate = null
                                currentFileSize = 0L
                                currentMd5 = null
                            }
                            "media:content" -> {
                                parser.getAttributeValue(null, "fileSize")
                                    ?.let { currentFileSize = it.toLongOrNull() ?: 0L }
                            }
                            "media:hash" -> {
                                pendingMd5 = parser.getAttributeValue(null, "algo") == "md5"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        textBuffer.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val text = textBuffer.toString().trim()
                        when (parser.name) {
                            "title" -> currentTitle = text
                            "link" -> currentLink = text
                            "pubDate" -> currentPubDate = text.takeIf { it.isNotBlank() }
                            "media:hash" -> {
                                if (pendingMd5) currentMd5 = text.takeIf { it.isNotBlank() }
                                pendingMd5 = false
                            }
                            "item" -> {
                                if (inItem && currentTitle.isNotBlank()) {
                                    items.add(
                                        RssItem(
                                            title = currentTitle,
                                            link = currentLink,
                                            pubDate = currentPubDate,
                                            fileSize = currentFileSize,
                                            md5 = currentMd5
                                        )
                                    )
                                }
                                inItem = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse error: ${e.message}")
        }
        return items
    }
}
