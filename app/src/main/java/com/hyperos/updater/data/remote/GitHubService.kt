package com.hyperos.updater.data.remote

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubResult(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?
)

@Singleton
class GitHubService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    // Package -> GitHub repo mapping for popular FOSS apps
    private val repoMap = mapOf(
        "org.videolan.vlc" to "videolan/vlc-android",
        "com.termux" to "termux/termux-app",
        "org.schabi.newpipe" to "TeamNewPipe/NewPipe",
        "org.telegram.messenger" to "Telegram-FOSS-Team/Telegram-FOSS",
        "com.spotify.music" to null, // proprietary
        "com.github.libretube" to "libre-tube/LibreTube",
        "com.duckduckgo.mobile.android" to "duckduckgo/Android",
        "org.mozilla.firefox" to "mozilla-mobile/firefox-android",
        "org.mozilla.fenix" to "mozilla-mobile/firefox-android",
        "de.danoeh.antennapod" to "AntennaPod/AntennaPod",
        "com.nextcloud.client" to "nextcloud/android",
        "org.kiwix.kiwixmobile" to "kiwix/kiwix-android",
        "com.owncloud.android" to "owncloud/android",
        "org.briarproject.briar.android" to "briar/briar",
        "com.laurencedawson.keepass2android" to "PhilippC/keepass2android",
        "com.osmand" to "osmandapp/OsmAnd",
        "net.osmand.plus" to "osmandapp/OsmAnd",
        "com.ichi2.anki" to "ankidroid/Anki-Android",
        "org.jellyfin.mobile" to "jellyfin/jellyfin-android",
        "com.vivaldi.browser" to null, // proprietary
        "org.torproject.torbrowser" to "guardianproject/tor-browser",
        "com.brave.browser" to "brave/brave-browser",
        "app.organicmaps" to "organicmaps/organicmaps",
        "net.ktnx.mobileledger" to "mariomastrandrea/mobileledger",
        "com.simplemobiletools.gallery.pro" to "SimpleMobileTools/Simple-Gallery",
        "org.fossify.gallery" to "FossifyOrg/Gallery",
        "com.github.kr328.clash" to "Kr328/ClashForAndroid",
        "com.gh4a" to "maniac103/OctoDroid"
    )

    suspend fun checkRelease(packageName: String): GitHubResult? = withContext(Dispatchers.IO) {
        val repo = repoMap[packageName] ?: return@withContext null
        try {
            val url = "https://api.github.com/repos/$repo/releases/latest"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val version = Regex("""(\d+\.\d+(?:\.\d+)*)""").find(tagName)?.value ?: tagName
            val versionCode = json.optLong("id", 0L)

            // Find best APK asset by ABI
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var bestUrl: String? = null
            var bestScore = -1

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                if (!name.endsWith(".apk")) continue

                // Score by ABI preference
                var score = 0
                if (name.contains("arm64-v8a")) score = 100
                else if (name.contains("arm64")) score = 80
                else if (name.contains("armeabi-v7a")) score = 60
                else if (name.contains("universal") || name.contains("all")) score = 40
                else score = 10

                if (score > bestScore) {
                    bestScore = score
                    bestUrl = dlUrl
                }
            }

            if (bestUrl != null) {
                Log.i("GitHub", "v$version for $packageName ($repo)")
                GitHubResult(version, versionCode, bestUrl)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
