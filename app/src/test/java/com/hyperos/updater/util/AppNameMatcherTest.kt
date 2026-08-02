package com.hyperos.updater.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameMatcherTest {

    // ── Tier 1: Exact normalized match ──────────────────────────────────────────

    @Test
    fun `exact match case-insensitive`() {
        assertTrue(AppNameMatcher.isMatch("Google Home", "Google Home"))
        assertTrue(AppNameMatcher.isMatch("Google Home", "google home"))
        assertTrue(AppNameMatcher.isMatch("GOOGLE HOME", "google home"))
    }

    @Test
    fun `exact match after normalization`() {
        assertTrue(AppNameMatcher.isMatch("Google Home", "googlehome"))
        assertTrue(AppNameMatcher.isMatch("Google-Home", "google home"))
        assertTrue(AppNameMatcher.isMatch("Google.Home", "google_home"))
    }

    // ── Tier 2: Whole-word containment ──────────────────────────────────────────

    @Test
    fun `google home does NOT match home assist`() {
        assertFalse(AppNameMatcher.isMatch("Google Home", "Home Assist"))
    }

    @Test
    fun `home assist does NOT match google home`() {
        assertFalse(AppNameMatcher.isMatch("Home Assist", "Google Home"))
    }

    @Test
    fun `word matches microsoft word as whole word`() {
        assertTrue(AppNameMatcher.isMatch("Word", "Microsoft Word"))
    }

    @Test
    fun `word does NOT match wordscapes`() {
        assertFalse(AppNameMatcher.isMatch("Word", "Wordscapes"))
    }

    @Test
    fun `wordscapes does NOT match word`() {
        assertFalse(AppNameMatcher.isMatch("Wordscapes", "Word"))
    }

    @Test
    fun `google matches google drive as whole word`() {
        // "Google" → word "google"; \bgoogle\b in "Google Drive" → true
        assertTrue(AppNameMatcher.isMatch("Google", "Google Drive"))
    }

    @Test
    fun `all installed words must be in candidate`() {
        // "Google News" → words: ["google", "news"]; "Google" only has "google"
        assertFalse(AppNameMatcher.isMatch("Google News", "Google"))
        // But "Google" → words: ["google"]; "Google News" has "google" ✓
        assertTrue(AppNameMatcher.isMatch("Google", "Google News"))
    }

    @Test
    fun `multi-word match`() {
        assertTrue(AppNameMatcher.isMatch("Google Play", "Google Play Store"))
        assertTrue(AppNameMatcher.isMatch("Play Store", "Google Play Store"))
    }

    // ── Edge cases ──────────────────────────────────────────────────────────────

    @Test
    fun `single char words are ignored`() {
        // "A Better App" → significant words: ["better", "app"] (len≥2 filter)
        assertTrue(AppNameMatcher.isMatch("A Better App", "Better App"))
        // "A" alone has no significant words → cannot match anything via tier 2
        assertFalse(AppNameMatcher.isMatch("A", "Better App"))
    }

    @Test
    fun `empty or blank installed name never matches`() {
        assertFalse(AppNameMatcher.isMatch("", "Google Home"))
        assertFalse(AppNameMatcher.isMatch("   ", "Google Home"))
    }

    @Test
    fun `special characters in names`() {
        assertTrue(AppNameMatcher.isMatch("Google's App", "Googles App"))
        // "com.google.app" → words: ["com", "google", "app"].
        // "Google" only contains "google" — "com" and "app" are missing → no match.
        assertFalse(AppNameMatcher.isMatch("com.google.app", "Google"))
    }

    @Test
    fun `real world examples`() {
        // Google Home vs Home Assist — the motivating bug
        assertFalse(AppNameMatcher.isMatch("Google Home", "Home Assist"))
        // Edge vs Microsoft Edge
        assertTrue(AppNameMatcher.isMatch("Edge", "Microsoft Edge"))
        // Chrome vs Google Chrome
        assertTrue(AppNameMatcher.isMatch("Chrome", "Google Chrome"))
        // Google vs Google Drive — whole-word
        assertTrue(AppNameMatcher.isMatch("Google", "Google Drive"))
        // Gemini vs Google Gemini
        assertTrue(AppNameMatcher.isMatch("Gemini", "Google Gemini"))
    }

    // ── findFirstMatch ──────────────────────────────────────────────────────────

    @Test
    fun `findFirstMatch returns index of first match`() {
        val candidates = listOf("Home Assist", "Google Home", "Google Keep")
        val idx = AppNameMatcher.findFirstMatch("Google Home", candidates)
        assertTrue(idx == 1)
    }

    @Test
    fun `findFirstMatch returns null when no match`() {
        val candidates = listOf("Home Assist", "Wordscapes", "Spotify")
        val idx = AppNameMatcher.findFirstMatch("Google Home", candidates)
        assertTrue(idx == null)
    }

    @Test
    fun `findFirstMatch returns null for empty list`() {
        assertTrue(AppNameMatcher.findFirstMatch("Google Home", emptyList()) == null)
    }
}
