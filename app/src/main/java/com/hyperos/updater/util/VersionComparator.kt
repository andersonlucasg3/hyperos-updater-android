package com.hyperos.updater.util

/**
 * Version comparison with line/qualifier awareness.
 *
 * ## Line (qualifier) concept
 *
 * A "line" is the non-numeric identity suffix that distinguishes build variants or regions.
 * Examples: `-A`, `-B`, `-global`, `-cn`, the MIUI region code `VMXMIXM`.
 *
 * Two versions belong to the **same line** when their qualifiers are equal after
 * case-folding and separator normalisation (`.`, `-`, `_` are treated equivalently).
 * Versions from **different lines are incomparable** — [isNewer] returns `false` in
 * both directions, so no update is offered across lines.
 *
 * A version **without** a qualifier (e.g. plain `1.2.3`) and a version **with** a
 * qualifier (e.g. `1.2.3-A`) are also considered different lines → incomparable.
 *
 * Within the same line numeric cores are compared as usual.  Qualifier text comparison
 * is exact (after normalisation): `beta` ≠ `rc.1` → different lines.  This is a
 * deliberate simplification — finer-grained prerelease ordering is out of scope.
 *
 * ## MIUI / HyperOS format
 *
 * For versions starting with `OS` (e.g. `OS2.0.206.0.VMXMIXM`) the region suffix
 * (the trailing letter segment) **is** the line.  Same region → numeric comparison
 * applies.  Different region → not newer.
 *
 * ## [compare] behaviour
 *
 * [compare] is used by [com.hyperos.updater.data.repository.AppUpdateRepositoryImpl.pickBest]
 * to rank candidate source versions *after* [isNewer] has already filtered to same-line
 * candidates.  For cross-line pairs [compare] returns `0` (equal ranking) so the
 * installed line is never displaced.
 */
object VersionComparator {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns `true` if both versions belong to the same line (equal qualifiers after
     * normalisation). Use this to gate versionCode-based decisions, which must never
     * cross lines — e.g. `0.0.0` and `0.0.0-R` are different lines.
     */
    fun isSameLine(versionA: String, versionB: String): Boolean =
        extractLine(versionA) == extractLine(versionB)

    /**
     * Returns `true` if [newVersion] is a genuine update over [currentVersion].
     *
     * Versions from different lines are **incomparable**: this method returns `false`
     * in both directions and no update should be offered.
     */
    fun isNewer(currentVersion: String, newVersion: String): Boolean {
        val curLine = extractLine(currentVersion)
        val newLine = extractLine(newVersion)
        if (curLine != newLine) return false  // different lines → incomparable

        if (currentVersion.startsWith("OS") && newVersion.startsWith("OS")) {
            return isMiuiVersionNewer(currentVersion, newVersion)
        }
        val cur = parseSemantic(currentVersion)
        val new = parseSemantic(newVersion)
        for (i in 0 until maxOf(cur.size, new.size)) {
            val c = cur.getOrElse(i) { 0 }
            val n = new.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    /**
     * VersionName-first comparator with versionCode tiebreaker.
     *
     * Returns positive if A is newer, negative if B is newer, zero if equal or
     * **incomparable** (different lines).
     *
     * Call sites: [com.hyperos.updater.data.repository.AppUpdateRepositoryImpl.pickBest]
     * uses this to rank already-filtered same-line source versions.  For cross-line
     * pairs returning `0` is the safe default — it never promotes a different-line
     * candidate over the installed line.
     */
    fun compare(
        versionNameA: String, versionCodeA: Long,
        versionNameB: String, versionCodeB: Long
    ): Int {
        val lineA = extractLine(versionNameA)
        val lineB = extractLine(versionNameB)
        if (lineA != lineB) return 0  // different lines → incomparable

        return when {
            isNewer(versionNameB, versionNameA) -> 1   // A is newer than B
            isNewer(versionNameA, versionNameB) -> -1  // B is newer than A
            versionCodeA > 0 && versionCodeB > 0 -> versionCodeA.compareTo(versionCodeB)
            else -> 0
        }
    }

    // ── Line extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the **line qualifier** from [version].
     *
     * Returns a lowercase, separator-normalised string.  An empty string means
     * "plain numeric version with no qualifier."
     *
     * Rules:
     * - MIUI format (starts with `OS`): the trailing non-numeric segment is the line.
     * - Semantic format: split on `.`, `-`, `_`; skip leading numeric-only segments;
     *   the rest (joined with `.`) is the line.
     */
    private fun extractLine(version: String): String {
        if (version.startsWith("OS")) return extractMiuiLine(version)
        return extractSemanticLine(version)
    }

    private fun extractMiuiLine(version: String): String {
        val withoutOS = version.removePrefix("OS")
        val parts = withoutOS.split(".")
        val last = parts.lastOrNull() ?: return ""
        // If the last segment has any letter, it's the region / line qualifier
        return if (last.any { it.isLetter() }) last.lowercase() else ""
    }

    private fun extractSemanticLine(version: String): String {
        val parts = version.split(".", "-", "_")
        val qualifierStart = parts.indexOfFirst { seg -> !seg.all { it.isDigit() } }
        if (qualifierStart == -1) return ""  // all numeric → no qualifier
        return parts.drop(qualifierStart).joinToString(".").lowercase()
    }

    // ── MIUI-specific helpers ────────────────────────────────────────────────

    private fun isMiuiVersionNewer(current: String, new: String): Boolean {
        val curParts = parseMiuiVersion(current) ?: return false
        val newParts = parseMiuiVersion(new) ?: return false
        for (i in 0 until maxOf(curParts.size, newParts.size)) {
            val c = curParts.getOrElse(i) { 0 }
            val n = newParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    private fun parseMiuiVersion(version: String): List<Int>? {
        val cleaned = version.removePrefix("OS")
        val parts = cleaned.split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 3) parts else null
    }

    // ── Semantic helpers ─────────────────────────────────────────────────────

    private fun parseSemantic(version: String): List<Int> =
        version.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
}
