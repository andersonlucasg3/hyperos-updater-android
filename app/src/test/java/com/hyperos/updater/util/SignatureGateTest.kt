package com.hyperos.updater.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureGateTest {

    // ── isAospTestKeyDn ────────────────────────────────────────────────────────

    @Test
    fun `exact AOSP test-key DN matches`() {
        assertTrue(SignatureGate.isAospTestKeyDn(
            "CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US"
        ))
    }

    @Test
    fun `AOSP DN case-insensitive matches`() {
        assertTrue(SignatureGate.isAospTestKeyDn(
            "cn=android, ou=android, o=android, l=mountain view, st=california, c=us"
        ))
    }

    @Test
    fun `AOSP DN with extra spaces matches`() {
        assertTrue(SignatureGate.isAospTestKeyDn(
            "CN=Android,  OU=Android,  O=Android,  L=Mountain View,  ST=California,  C=US"
        ))
    }

    @Test
    fun `component-based match with reordered DN`() {
        // Same logical identity, different attribute order
        assertTrue(SignatureGate.isAospTestKeyDn(
            "O=Android, CN=Android, L=Mountain View, ST=California, C=US"
        ))
    }

    @Test
    fun `AOSP DN with extra attributes still matches`() {
        assertTrue(SignatureGate.isAospTestKeyDn(
            "CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US, EMAILADDRESS=android@android.com"
        ))
    }

    // ── Negative cases ─────────────────────────────────────────────────────────

    @Test
    fun `Xiaomi official cert does NOT match`() {
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=Xiaomi Inc., O=Xiaomi"
        ))
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=Xiaomi, OU=MIUI, O=Xiaomi, L=Beijing, ST=Beijing, C=CN"
        ))
    }

    @Test
    fun `Google cert does NOT match`() {
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=Google LLC, O=Google LLC, L=Mountain View, ST=California, C=US"
        ))
    }

    @Test
    fun `empty string does NOT match`() {
        assertFalse(SignatureGate.isAospTestKeyDn(""))
    }

    @Test
    fun `random DN does NOT match`() {
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=Unknown, OU=Dev, O=SomeCorp, L=NYC, ST=NY, C=US"
        ))
    }

    @Test
    fun `CN Android but O not Android does NOT match`() {
        // Need BOTH CN=Android AND O=Android for a tight match
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=Android, O=SomeOtherOrg, L=Mountain View, C=US"
        ))
    }

    @Test
    fun `O Android but CN not Android does NOT match`() {
        assertFalse(SignatureGate.isAospTestKeyDn(
            "CN=SomeApp, O=Android, L=Mountain View, C=US"
        ))
    }
}
