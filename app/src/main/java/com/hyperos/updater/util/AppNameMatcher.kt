package com.hyperos.updater.util

object AppNameMatcher {

    /** Normalize for comparison: lowercase, trim, collapse non-alphanumeric chars. */
    fun normalize(name: String): String =
        name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    /** Extract significant words (len ≥ 2) from a name. */
    private fun significantWords(name: String): List<String> =
        name.lowercase().trim()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }

    /**
     * Returns true if [candidateName] is a plausible match for [installedName].
     *
     * Tiers (first true wins):
     * 1. Exact normalized match (case-insensitive, non-alphanumeric stripped)
     * 2. ALL significant words (len ≥ 2) of the installed name appear as
     *    whole words (\\b boundary) in the candidate name
     *
     * Note: there is intentionally NO startsWith / prefix tier — that would
     * falsely match "Word" vs "Wordscapes" ("wordscapes".startsWith("word")).
     * Real prefix relationships like "Google" vs "Google Drive" are handled by
     * tier 2 (whole-word containment: \\bgoogle\\b matches "Google Drive").
     *
     * Examples:
     * - "Google Home" vs "Home Assist" → FALSE (google not in Home Assist)
     * - "Home Assist" vs "Google Home" → FALSE (assist not in Google Home)
     * - "Word" vs "Microsoft Word" → TRUE (word matches as whole word)
     * - "Word" vs "Wordscapes" → FALSE (no word boundary after "Word")
     * - "Google Home" vs "googlehome" → TRUE (tier 1 exact normalized)
     * - "Google" vs "Google Drive" → TRUE (\\bgoogle\\b matches)
     */
    fun isMatch(installedName: String, candidateName: String): Boolean {
        val normInstalled = normalize(installedName)
        val normCandidate = normalize(candidateName)
        if (normInstalled.isEmpty()) return false

        // Tier 1: exact normalized match
        if (normInstalled == normCandidate) return true

        // Tier 2: all significant words of installed appear as whole words in candidate
        val installedWords = significantWords(installedName)
        if (installedWords.isEmpty()) return false

        return installedWords.all { word ->
            Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(candidateName)
        }
    }

    /**
     * Returns the index of the first candidate that matches [installedName],
     * or null if none match. A missed source is better than a WRONG app.
     */
    fun findFirstMatch(installedName: String, candidateNames: List<String>): Int? =
        candidateNames.indexOfFirst { isMatch(installedName, it) }.takeIf { it >= 0 }
}
