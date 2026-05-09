package com.hyperos.updater.data.remote.dto

data class ApkMirrorRssItem(
    val title: String,
    val link: String,
    val pubDate: String
) {
    val version: String?
        get() {
            // APKMirror titles: "App Name 1.0.9 (variant)" or "Chrome 120.0.6099.144 (variant)"
            // Find version pattern: digits.digits[.digits...] appearing after the app name
            val versionRegex = Regex("""\b(\d+\.\d+(?:\.\d+)*)\b""")
            val matches = versionRegex.findAll(title).toList()
            if (matches.isEmpty()) return null
            // The first version-like number is the app version
            return matches.first().groupValues[1]
        }
}
