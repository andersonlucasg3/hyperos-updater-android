package com.hyperos.updater.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOsDetectorTest {

    // ── isWearOsListing ────────────────────────────────────────────────────────

    @Test
    fun `null and blank text are not Wear`() {
        assertFalse(WearOsDetector.isWearOsListing(null))
        assertFalse(WearOsDetector.isWearOsListing(""))
        assertFalse(WearOsDetector.isWearOsListing("   "))
    }

    @Test
    fun `wear os with space`() {
        assertTrue(WearOsDetector.isWearOsListing("Spotify Wear OS"))
        assertTrue(WearOsDetector.isWearOsListing("WhatsApp wear os"))
    }

    @Test
    fun `wearos as one word`() {
        assertTrue(WearOsDetector.isWearOsListing("Spotify WearOS"))
        assertTrue(WearOsDetector.isWearOsListing("SomeApp wearos edition"))
    }

    @Test
    fun `wear_os with underscore`() {
        assertTrue(WearOsDetector.isWearOsListing("App Wear_OS variant"))
        assertTrue(WearOsDetector.isWearOsListing("wear_OS"))
    }

    @Test
    fun `parenthesized wear`() {
        assertTrue(WearOsDetector.isWearOsListing("WhatsApp (Wear)"))
        assertTrue(WearOsDetector.isWearOsListing("App (wear)"))
        assertTrue(WearOsDetector.isWearOsListing("(Wear) App"))
    }

    @Test
    fun `android wear`() {
        assertTrue(WearOsDetector.isWearOsListing("Android Wear version"))
        assertTrue(WearOsDetector.isWearOsListing("android  wear"))
    }

    @Test
    fun `wear watch`() {
        assertTrue(WearOsDetector.isWearOsListing("Wear watch app"))
        assertTrue(WearOsDetector.isWearOsListing("App for Wear Watch"))
    }

    @Test
    fun `plain wear is NOT matched`() {
        // "wear" alone should not trigger — avoids false positives
        assertFalse(WearOsDetector.isWearOsListing("I wear this"))
        assertFalse(WearOsDetector.isWearOsListing("Wear it now"))
    }

    @Test
    fun `swear is NOT matched`() {
        assertFalse(WearOsDetector.isWearOsListing("I swear this is true"))
        assertFalse(WearOsDetector.isWearOsListing("swear"))
    }

    @Test
    fun `normal app names are not matched`() {
        assertFalse(WearOsDetector.isWearOsListing("Spotify"))
        assertFalse(WearOsDetector.isWearOsListing("WhatsApp Messenger"))
        assertFalse(WearOsDetector.isWearOsListing("Google Chrome 120.0.6099.144"))
        assertFalse(WearOsDetector.isWearOsListing("Netflix"))
    }

    @Test
    fun `version strings without wear are not matched`() {
        assertFalse(WearOsDetector.isWearOsListing("1.2.3"))
        assertFalse(WearOsDetector.isWearOsListing("OS2.0.206.0.VMXMIXM"))
        assertFalse(WearOsDetector.isWearOsListing("v1.0.0-beta"))
    }

    @Test
    fun `case insensitive matching`() {
        assertTrue(WearOsDetector.isWearOsListing("WEAR OS"))
        assertTrue(WearOsDetector.isWearOsListing("Wear Os"))
        assertTrue(WearOsDetector.isWearOsListing("wearOS"))
        assertTrue(WearOsDetector.isWearOsListing("ANDROID WEAR"))
        assertTrue(WearOsDetector.isWearOsListing("(WEAR)"))
    }

    @Test
    fun `wear embedded in title with other text`() {
        assertTrue(WearOsDetector.isWearOsListing("Spotify - Wear OS 1.2.3"))
        assertTrue(WearOsDetector.isWearOsListing("Wear OS by Google"))
    }

    // ── containsWearFeature ────────────────────────────────────────────────────

    @Test
    fun `UTF-8 marker found`() {
        val bytes = "some binary data android.hardware.type.watch more data".toByteArray(Charsets.UTF_8)
        assertTrue(WearOsDetector.containsWearFeature(bytes))
    }

    @Test
    fun `UTF-16LE marker found`() {
        val marker = "android.hardware.type.watch"
        val utf16le = marker.toByteArray(Charsets.UTF_16LE)
        // Build a buffer with some padding around the marker
        val buffer = ByteArray(utf16le.size + 10)
        for (i in utf16le.indices) buffer[i + 5] = utf16le[i]
        assertTrue(WearOsDetector.containsWearFeature(buffer))
    }

    @Test
    fun `marker not present`() {
        val bytes = "some random binary data without the feature".toByteArray(Charsets.UTF_8)
        assertFalse(WearOsDetector.containsWearFeature(bytes))
    }

    @Test
    fun `empty byte array`() {
        assertFalse(WearOsDetector.containsWearFeature(ByteArray(0)))
    }

    @Test
    fun `partial marker not matched`() {
        val bytes = "android.hardware.type.watc".toByteArray(Charsets.UTF_8)
        assertFalse(WearOsDetector.containsWearFeature(bytes))
    }

    @Test
    fun `marker at start of byte array`() {
        val markerBytes = "android.hardware.type.watch".toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(markerBytes.size + 3)
        System.arraycopy(markerBytes, 0, bytes, 0, markerBytes.size)
        assertTrue(WearOsDetector.containsWearFeature(bytes))
    }

    @Test
    fun `marker at end of byte array`() {
        val markerBytes = "android.hardware.type.watch".toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(markerBytes.size + 3)
        System.arraycopy(markerBytes, 0, bytes, 3, markerBytes.size)
        assertTrue(WearOsDetector.containsWearFeature(bytes))
    }

    @Test
    fun `needle larger than haystack returns false`() {
        val bytes = "short".toByteArray(Charsets.UTF_8)
        assertFalse(WearOsDetector.containsWearFeature(bytes))
    }
}
