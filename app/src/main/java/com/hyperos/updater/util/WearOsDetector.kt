package com.hyperos.updater.util

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Shared Wear OS detection utilities.
 *
 * Two layers:
 * 1. **Listing filter** — [isWearOsListing] checks text like app titles/variant names
 *    for Wear OS markers. Used at search time and in history lists to hide Wear variants.
 * 2. **Manifest scan** — [scanApkForWearFeature] inspects the downloaded APK/bundle's
 *    AndroidManifest.xml for the `android.hardware.type.watch` feature declaration.
 *    This is the hard install guard.
 */
object WearOsDetector {

    // ── Layer 1: Listing filter ────────────────────────────────────────────────

    /**
     * Case-insensitive regex matching Wear OS markers in app titles / variant names.
     *
     * Matches:
     * - "wear os", "wear_os", "wearos", "wearOS"
     * - "(wear)" parenthesized
     * - "android wear"
     * - "wear watch"
     *
     * Does NOT match plain "wear" inside unrelated words (e.g. "swear") because
     * of the `\b` word boundary on "wear".
     */
    private val WEAR_OS_LISTING_PATTERN = Regex(
        """(?i)\bwear[\s_]*os\b|\bwearos\b|\(wear\)|\bandroid[\s_]+wear\b|\bwear[\s_]+watch\b"""
    )

    /** Returns `true` when [text] contains a Wear OS marker and should be filtered out. */
    fun isWearOsListing(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return WEAR_OS_LISTING_PATTERN.containsMatchIn(text)
    }

    // ── Layer 2: Manifest scan (hard install guard) ────────────────────────────

    /**
     * Scans an APK or split-APK bundle file for the Wear OS feature declaration.
     *
     * For single APKs: reads `AndroidManifest.xml` from the ZIP root.
     * For bundles (XAPK/APKM with no root manifest): reads the first inner `.apk`
     * entry's manifest recursively.
     *
     * Returns `true` if `android.hardware.type.watch` is found.
     * Returns `false` if the file cannot be read (fail-soft: let install proceed).
     */
    fun scanApkForWearFeature(file: File): Boolean {
        return try {
            ZipFile(file).use { zip -> scanZipForWearFeature(zip) }
        } catch (_: Exception) {
            false // fail-soft: if we can't read the zip, don't block install
        }
    }

    /**
     * Pure byte-level search for the Wear OS feature marker.
     * Searches both UTF-8 and UTF-16LE encodings (Android's binary AXML can use either).
     * Extracted as a public function for unit testing.
     */
    fun containsWearFeature(bytes: ByteArray): Boolean {
        val marker = "android.hardware.type.watch"

        // UTF-8: marker bytes = marker as-is
        if (findByteSequence(bytes, marker.encodeToByteArray())) return true

        // UTF-16LE: each char → 2 bytes (low-byte, zero)
        val utf16le = marker.toByteArray(Charsets.UTF_16LE)
        if (findByteSequence(bytes, utf16le)) return true

        return false
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun scanZipForWearFeature(zip: ZipFile): Boolean {
        // Single APK: check root AndroidManifest.xml
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
        if (manifestEntry != null) {
            val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            return containsWearFeature(bytes)
        }

        // Bundle (XAPK/APKM): no root manifest → scan inner .apk entries
        val apkEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
            .toList()

        for (apkEntry in apkEntries) {
            try {
                val innerBytes = zip.getInputStream(apkEntry).use { it.readBytes() }
                if (scanInnerApkBytes(innerBytes)) return true
            } catch (_: Exception) { /* try next inner apk */ }
        }

        return false
    }

    private fun scanInnerApkBytes(apkBytes: ByteArray): Boolean {
        return try {
            ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.equals("AndroidManifest.xml", ignoreCase = true)) {
                        val manifestBytes = zis.readBytes()
                        return containsWearFeature(manifestBytes)
                    }
                    entry = zis.nextEntry
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun findByteSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
