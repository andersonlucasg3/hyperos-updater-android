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
 * ## Build metadata (VCS hashes)
 *
 * Segments that look like VCS/build hashes (`[tg]?[0-9a-f]{6,}`, case-insensitive) are
 * treated as **build metadata**, not version-line qualifiers.  They are skipped when
 * extracting the qualifier, so e.g. Tailscale versions `1.98.8-t07c51dd63-g3b24a1d04`
 * and `1.102.0-t11aabbcc22-g4455667788` are both plain-line (empty qualifier) and
 * compare by numeric core alone.
 *
 * A version whose *only* non-numeric segments are hash-like has **no qualifier** — it
 * is on the same line as a plain numeric version.  If there are non-hash qualifier
 * segments (e.g. `1.2.3-beta-t07c51dd63`) the non-hash segments still form the line.
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

    /**
     * Extracts the semantic line qualifier, skipping VCS/build hash segments.
     *
     * Hash-like segments (matching `[tg]?[0-9a-f]{6,}` case-insensitively) are
     * treated as build metadata — they are ignored both when finding the qualifier
     * start and when joining the qualifier tail.  A version whose only non-numeric
     * segments are hash-like has NO qualifier (empty string).
     *
     * Example: `1.98.8-t07c51dd63-g3b24a1d04` → qualifier `""` (plain line).
     */
    private fun extractSemanticLine(version: String): String {
        val parts = version.split(".", "-", "_")
        val qualifierStart = parts.indexOfFirst { seg ->
            !seg.all { it.isDigit() } && !isHashLikeSegment(seg)
        }
        if (qualifierStart == -1) return ""  // all numeric or hash → no qualifier
        return parts.drop(qualifierStart)
            .filterNot { isHashLikeSegment(it) }
            .joinToString(".")
            .lowercase()
    }

    /**
     * Returns `true` when [seg] looks like a VCS/build hash rather than a version
     * qualifier.
     *
     * Matches (case-insensitive) `[tg]?[0-9a-f]{6,}`:
     * - Optional `t` (tag) or `g` (git-describe) prefix
     * - Followed by ≥ 6 hexadecimal characters
     *
     * Examples: `t07c51dd63`, `g3b24a1d04`, bare `07c51dd63`, `aaaaaa`.
     * Non-matches: `global` (non-hex letters), `beta`/`rc` (too short, non-hex),
     * `A`/`cn` (too short), `build` (non-hex).
     */
    private fun isHashLikeSegment(seg: String): Boolean =
        seg.matches(Regex("^[tg]?[0-9a-f]{6,}$", RegexOption.IGNORE_CASE))

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
