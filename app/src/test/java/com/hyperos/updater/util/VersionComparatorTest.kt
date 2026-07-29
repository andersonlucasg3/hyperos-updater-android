package com.hyperos.updater.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionComparatorTest {

    // ── isNewer – plain semantic versions ────────────────────────────────────

    @Test
    fun `isNewer – 1_2_10 is newer than 1_2_3 (numeric, not lexicographic)`() {
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2.10"))
    }

    @Test
    fun `isNewer – 2_0 is newer than 1_99_9`() {
        assertTrue(VersionComparator.isNewer("1.99.9", "2.0"))
    }

    @Test
    fun `isNewer – equal versions are not newer`() {
        assertFalse(VersionComparator.isNewer("1.2.3", "1.2.3"))
        assertFalse(VersionComparator.isNewer("3.14.0", "3.14.0"))
    }

    @Test
    fun `isNewer – symmetric check on equal versions`() {
        assertFalse(VersionComparator.isNewer("2.0.0", "2.0.0"))
    }

    // ── isNewer – different segment counts ───────────────────────────────────

    @Test
    fun `isNewer – shorter padded with zero means equal`() {
        assertFalse(VersionComparator.isNewer("1.2", "1.2.0"))
        assertFalse(VersionComparator.isNewer("1.2.0", "1.2"))
    }

    @Test
    fun `isNewer – longer version with extra non-zero segment is newer`() {
        assertTrue(VersionComparator.isNewer("1.2", "1.2.1"))
        assertFalse(VersionComparator.isNewer("1.2.1", "1.2"))
    }

    // ── isNewer – line / qualifier semantics ─────────────────────────────────

    @Test
    fun `isNewer – different qualifiers are incomparable (both directions false)`() {
        // 0.0.0-A vs 0.0.0-B: different lines → not newer either way
        assertFalse(VersionComparator.isNewer("0.0.0-A", "0.0.0-B"))
        assertFalse(VersionComparator.isNewer("0.0.0-B", "0.0.0-A"))
    }

    @Test
    fun `isNewer – qualified vs plain version are different lines`() {
        // 0.0.0-A vs 0.0.0: one has qualifier, one doesn't → different lines
        assertFalse(VersionComparator.isNewer("0.0.0", "0.0.0-A"))
        assertFalse(VersionComparator.isNewer("0.0.0-A", "0.0.0"))
    }

    @Test
    fun `isNewer – same qualifier with newer numeric core is newer`() {
        // 1.2.3-global vs 1.2.4-global: same line "global", higher numeric → newer
        assertTrue(VersionComparator.isNewer("1.2.3-global", "1.2.4-global"))
        assertFalse(VersionComparator.isNewer("1.2.4-global", "1.2.3-global"))
    }

    @Test
    fun `isNewer – same qualifier but older numeric core is not newer`() {
        assertFalse(VersionComparator.isNewer("1.2.4-global", "1.2.3-global"))
    }

    @Test
    fun `isNewer – different qualifiers block update even with higher numeric core`() {
        // 1.2.3-global vs 1.2.4-cn: different lines → not newer
        assertFalse(VersionComparator.isNewer("1.2.3-global", "1.2.4-cn"))
        assertFalse(VersionComparator.isNewer("1.2.4-cn", "1.2.3-global"))
    }

    @Test
    fun `isNewer – case-insensitive qualifier matching`() {
        // 1.0-Global vs 1.0.1_global: same line after normalisation, newer numeric
        assertTrue(VersionComparator.isNewer("1.0-Global", "1.0.1_global"))
    }

    @Test
    fun `isNewer – separator-normalised qualifier matching (dash vs dot vs underscore)`() {
        // 1.0-global vs 1.0.1.global → same line "global", newer numeric
        assertTrue(VersionComparator.isNewer("1.0-global", "1.0.1.global"))
    }

    // ── isNewer – prerelease / suffix: different qualifiers = different lines ─

    @Test
    fun `isNewer – beta vs rc are different lines (incomparable)`() {
        // 1.2.3-beta (line "beta") vs 1.2.3-rc.1 (line "rc.1") → different lines
        assertFalse(VersionComparator.isNewer("1.2.3-beta", "1.2.3-rc.1"))
        assertFalse(VersionComparator.isNewer("1.2.3-rc.1", "1.2.3-beta"))
    }

    @Test
    fun `isNewer – alpha with different build numbers are different lines`() {
        // 1.0-alpha.1 (line "alpha.1") vs 1.0-alpha.2 (line "alpha.2") → different lines
        assertFalse(VersionComparator.isNewer("1.0-alpha.1", "1.0-alpha.2"))
        assertFalse(VersionComparator.isNewer("1.0-alpha.2", "1.0-alpha.1"))
    }

    @Test
    fun `isNewer – build metadata with different numbers are different lines`() {
        // 2.0-build.5 (line "build.5") vs 2.0-build.10 (line "build.10") → different lines
        assertFalse(VersionComparator.isNewer("2.0-build.5", "2.0-build.10"))
        assertFalse(VersionComparator.isNewer("2.0-build.10", "2.0-build.5"))
    }

    // ── isNewer – MIUI / HyperOS formats ─────────────────────────────────────

    @Test
    fun `isNewer – MIUI OS2 higher build number is newer (same region)`() {
        assertTrue(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS2.0.207.0.VMXMIXM"))
        assertFalse(VersionComparator.isNewer("OS2.0.207.0.VMXMIXM", "OS2.0.206.0.VMXMIXM"))
    }

    @Test
    fun `isNewer – MIUI OS2 minor segment difference (same region)`() {
        assertTrue(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS2.1.206.0.VMXMIXM"))
    }

    @Test
    fun `isNewer – MIUI OS2 major segment difference (same region)`() {
        assertTrue(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS3.0.206.0.VMXMIXM"))
    }

    @Test
    fun `isNewer – MIUI different region suffixes are different lines (incomparable)`() {
        // Same numeric build, different region → not newer in either direction
        assertFalse(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS2.0.206.0.VMXCNXM"))
        assertFalse(VersionComparator.isNewer("OS2.0.206.0.VMXCNXM", "OS2.0.206.0.VMXMIXM"))
    }

    @Test
    fun `isNewer – MIUI different region with higher build number is still not newer`() {
        // Global installed, Chinese with higher build → must NOT be offered as update
        assertFalse(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS2.0.207.0.VMXCNXM"))
        assertFalse(VersionComparator.isNewer("OS2.0.207.0.VMXCNXM", "OS2.0.206.0.VMXMIXM"))
    }

    @Test
    fun `isNewer – MIUI OS1 format (same region)`() {
        // OS1.0.500.0.ABCDEFG vs OS1.0.501.0.XYZ → different regions (abcdefg vs xyz) → incomparable
        assertFalse(VersionComparator.isNewer("OS1.0.500.0.ABCDEFG", "OS1.0.501.0.XYZ"))
        assertFalse(VersionComparator.isNewer("OS1.0.501.0.XYZ", "OS1.0.500.0.ABCDEFG"))
    }

    @Test
    fun `isNewer – MIUI OS1 format same region`() {
        // Same region suffix, higher build → newer
        assertTrue(VersionComparator.isNewer("OS1.0.500.0.XYZ", "OS1.0.501.0.XYZ"))
    }

    @Test
    fun `isNewer – MIUI version with fewer than 3 numeric parts is rejected`() {
        assertFalse(VersionComparator.isNewer("OS2.0", "OS2.0.100.0.VMXMIXM"))
        assertFalse(VersionComparator.isNewer("OS2.0.100.0.VMXMIXM", "OS2.0"))
    }

    @Test
    fun `isNewer – MIUI with all-numeric 4th segment differs`() {
        // Both have no region suffix (all numeric) → same line (empty)
        assertTrue(VersionComparator.isNewer("OS2.0.206.0", "OS2.0.206.1"))
    }

    @Test
    fun `isNewer – MIUI with region suffix vs MIUI without are different lines`() {
        // OS2.0.206.0 (line "") vs OS2.0.206.0.VMXMIXM (line "vmxmixm") → incomparable
        assertFalse(VersionComparator.isNewer("OS2.0.206.0", "OS2.0.206.0.VMXMIXM"))
        assertFalse(VersionComparator.isNewer("OS2.0.206.0.VMXMIXM", "OS2.0.206.0"))
    }

    // ── isNewer – garbage / edge-case inputs ──────────────────────────────────

    @Test
    fun `isNewer – empty strings treated as zero, valid version is newer`() {
        assertFalse(VersionComparator.isNewer("", ""))
        assertTrue(VersionComparator.isNewer("", "1.0"))
        assertFalse(VersionComparator.isNewer("1.0", ""))
    }

    @Test
    fun `isNewer – completely non-numeric strings treated as zero`() {
        // Both non-numeric → line "" (all segments empty/non-numeric? let's verify:
        // "abc" → split → ["abc"] → "abc" has letter → qualifier "abc". "def" → "def". Different → false)
        // Actually: "abc" → parts ["abc"], "abc".all { it.isDigit() } → false → qualStart = 0 → "abc"
        // "def" → "def". Different lines. So now these are incomparable.
        assertFalse(VersionComparator.isNewer("abc", "def"))
        // "abc" (line "abc") vs "1.0" (line "") → different lines → false
        assertFalse(VersionComparator.isNewer("abc", "1.0"))
        assertFalse(VersionComparator.isNewer("1.0", "abc"))
    }

    @Test
    fun `isNewer – mixed numeric and non-numeric are different lines`() {
        // "1.abc.3" → qualifier "abc.3"; "1.2.3" → qualifier "" → different lines → incomparable
        assertFalse(VersionComparator.isNewer("1.2.3", "1.abc.3"))
        assertFalse(VersionComparator.isNewer("1.abc.3", "1.2.3"))
    }

    @Test
    fun `isNewer – only dots, no numbers, treated as zero`() {
        // "..." → split → ["", "", "", ""] → all empty → all "digit" → line "" → same line
        assertFalse(VersionComparator.isNewer("...", "..."))
        assertTrue(VersionComparator.isNewer("...", "1.0"))
    }

    @Test
    fun `isNewer – single numeric segment`() {
        assertTrue(VersionComparator.isNewer("0", "1"))
        assertFalse(VersionComparator.isNewer("1", "0"))
    }

    // ── isNewer – cross-format (semantic vs MIUI) ────────────────────────────

    @Test
    fun `isNewer – semantic version vs MIUI version are different lines`() {
        // "1.0.0" (line "") vs "OS1.0.500.0.XYZ" (line "xyz") → different lines → incomparable
        assertFalse(VersionComparator.isNewer("OS1.0.500.0.XYZ", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "OS1.0.500.0.XYZ"))
    }

    // ── compare – versionName-first with versionCode tiebreaker ───────────────

    @Test
    fun `compare – higher versionCode with older versionName does NOT win`() {
        val result = VersionComparator.compare("1.0.0", 100, "0.9.0", 1)
        assertTrue(result > 0)
    }

    @Test
    fun `compare – older versionName with higher versionCode loses to newer versionName`() {
        val result = VersionComparator.compare("0.9.0", 100, "1.0.0", 1)
        assertTrue(result < 0)
    }

    @Test
    fun `compare – equal versionName, higher versionCode wins as tiebreaker`() {
        val result = VersionComparator.compare("1.0.0", 1, "1.0.0", 100)
        assertTrue(result < 0)
    }

    @Test
    fun `compare – equal versionName, lower versionCode loses`() {
        val result = VersionComparator.compare("1.0.0", 100, "1.0.0", 1)
        assertTrue(result > 0)
    }

    @Test
    fun `compare – equal versionName, same versionCode is tie`() {
        assertEquals(0, VersionComparator.compare("1.0.0", 5, "1.0.0", 5))
    }

    @Test
    fun `compare – equal versionName, both versionCodes zero is tie`() {
        assertEquals(0, VersionComparator.compare("2.0", 0, "2.0", 0))
    }

    @Test
    fun `compare – equal versionName, one versionCode zero is tie`() {
        assertEquals(0, VersionComparator.compare("3.14", 0, "3.14", 100))
        assertEquals(0, VersionComparator.compare("3.14", 100, "3.14", 0))
    }

    @Test
    fun `compare – both names and codes equal but codes zero`() {
        assertEquals(0, VersionComparator.compare("1.0.0", 0, "1.0.0", 0))
    }

    // ── compare – MIUI versions ──────────────────────────────────────────────

    @Test
    fun `compare – MIUI versionName difference overrides versionCode (same region)`() {
        val result = VersionComparator.compare("OS2.0.206.0.VMXMIXM", 999, "OS2.0.207.0.VMXMIXM", 1)
        assertTrue(result < 0) // B has newer name → B wins
    }

    @Test
    fun `compare – MIUI different regions are incomparable (returns 0)`() {
        // Same numeric build, different region → comparable returns 0 (incomparable)
        val result = VersionComparator.compare(
            "OS2.0.206.0.VMXMIXM", 10,
            "OS2.0.206.0.VMXCNXM", 20
        )
        assertEquals(0, result)
    }

    // ── compare – cross-line semantics ───────────────────────────────────────

    @Test
    fun `compare – different semantic qualifiers return 0 (incomparable)`() {
        val result = VersionComparator.compare("1.0.0-global", 10, "1.0.0-cn", 20)
        assertEquals(0, result)
    }

    @Test
    fun `compare – qualified vs plain return 0 (incomparable)`() {
        val result = VersionComparator.compare("1.0.0-A", 10, "1.0.0", 20)
        assertEquals(0, result)
    }

    @Test
    fun `compare – same qualifier with higher versionCode wins`() {
        // Same line "global", same versionName, code tiebreaker
        val result = VersionComparator.compare("1.0.0-global", 10, "1.0.0-global", 20)
        assertTrue(result < 0) // B has higher code
    }

    @Test
    fun `compare – same qualifier with newer versionName wins`() {
        val result = VersionComparator.compare("1.0.0-global", 999, "1.0.1-global", 1)
        assertTrue(result < 0) // B has newer name
    }

    // ── isNewer – plain vs plain (regression guards) ─────────────────────────

    @Test
    fun `isNewer – plain numeric versions still work as before`() {
        assertTrue(VersionComparator.isNewer("1.0.0", "2.0.0"))
        assertFalse(VersionComparator.isNewer("2.0.0", "1.0.0"))
        assertTrue(VersionComparator.isNewer("3.14.0", "3.14.1"))
        assertFalse(VersionComparator.isNewer("3.14.1", "3.14.0"))
    }

    // ── isSameLine ────────────────────────────────────────────────────────────

    @Test
    fun `isSameLine – plain vs qualified are different lines`() {
        assertFalse(VersionComparator.isSameLine("0.0.0", "0.0.0-R"))
        assertFalse(VersionComparator.isSameLine("0.0.0-R", "0.0.0"))
    }

    @Test
    fun `isSameLine – same qualifier regardless of numeric core`() {
        assertTrue(VersionComparator.isSameLine("1.2.3-global", "9.9.9-global"))
        assertTrue(VersionComparator.isSameLine("0.0.0-R", "0.0.1-R"))
    }

    @Test
    fun `isSameLine – normalisation applies`() {
        assertTrue(VersionComparator.isSameLine("1.0-Global", "2.0_global"))
        assertFalse(VersionComparator.isSameLine("1.0.0-A", "1.0.0-B"))
    }

    @Test
    fun `isSameLine – MIUI region suffixes`() {
        assertTrue(VersionComparator.isSameLine("OS2.0.206.0.VMXMIXM", "OS2.0.207.0.VMXMIXM"))
        assertFalse(VersionComparator.isSameLine("OS2.0.206.0.VMXMIXM", "OS2.0.207.0.VMXCNXM"))
    }
}
