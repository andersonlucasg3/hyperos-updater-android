package com.hyperos.updater.data.remote

import com.hyperos.updater.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

data class ApkComboResult(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?
)

@Singleton
class ApkComboService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun search(packageName: String): ApkComboResult? = withContext(Dispatchers.IO) {
        try {
            // APKCombo search by package name
            val url = "https://apkcombo.com/search/$packageName"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkUtils.USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val doc = Jsoup.parse(html)

            // Find the first app card that matches our package
            val cards = doc.select(".l_item")
            for (card in cards) {
                val link = card.select("a[href]").firstOrNull()?.attr("href") ?: continue
                if (!link.contains(packageName)) continue

                val name = card.select(".title").firstOrNull()?.text()
                    ?: card.select("h1").firstOrNull()?.text()
                    ?: card.select("[itemprop=name]").firstOrNull()?.text()
                    ?: continue

                val versionText = card.select(".version").firstOrNull()?.text()
                    ?: card.select("[itemprop=version]").firstOrNull()?.text()
                    ?: ""

                // Parse version: extract first digit.digit... pattern
                val versionName = Regex("""(\d+\.\d+(?:\.\d+)*)""").find(versionText)?.value ?: versionText
                val versionCode = versionName.replace(".", "").take(8).toLongOrNull() ?: 0L

                val detailUrl = if (link.startsWith("http")) link else "https://apkcombo.com$link"
                val downloadUrl = "$detailUrl/download/apk"

                return@withContext ApkComboResult(
                    appName = name,
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = downloadUrl,
                    fileSize = null
                )
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
