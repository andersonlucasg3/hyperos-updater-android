package com.hyperos.updater.util

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Detects APKs signed with the AOSP default platform test-key.
 *
 * xiaomi.eu ROMs re-sign ALL system apps with this key, making official
 * Xiaomi-signed (MemeOs) APKs incompatible (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
 * This gate lets us mark those apps and skip the doomed MemeOs fetch.
 */
object SignatureGate {

    private const val TAG = "SignatureGate"

    /** The AOSP default test-key DN — used by xiaomi.eu to re-sign system apps. */
    private const val AOSP_TEST_KEY_DN =
        "CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US"

    // ── Android side (requires PackageManager) ──────────────────────────────

    /**
     * Returns `true` when [packageName] is signed by the AOSP default platform
     * test-key (i.e. its signing certificate's subject DN matches the known
     * AOSP DN). Fails soft — any exception returns `false`.
     */
    fun isAospTestKeySigned(pm: PackageManager, packageName: String): Boolean {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    val signers = signingInfo.apkContentsSigners
                    if (signers.isNotEmpty()) signers.toList()
                    else signingInfo.signingCertificateHistory?.toList() ?: emptyList()
                } else emptyList()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.toList() ?: emptyList()
            }

            val cf = CertificateFactory.getInstance("X.509")
            signatures.any { signatureBytes ->
                try {
                    val cert = cf.generateCertificate(signatureBytes.toByteArray().inputStream()) as X509Certificate
                    isAospTestKeyDn(cert.subjectDN.name)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Signature check failed for $packageName: ${e.message}")
            false
        }
    }

    // ── Pure function (testable without Android / BouncyCastle) ─────────────

    /**
     * Returns `true` when [dn] is the AOSP default test-key DN.
     *
     * Match is tight: we require the organisation ("O") and common name ("CN")
     * both to be "Android" — this avoids false-positives on Xiaomi official
     * certs (which contain "Xiaomi" somewhere in the DN).
     */
    fun isAospTestKeyDn(dn: String): Boolean {
        // Full match is simplest and most precise — the AOSP DN is well-known.
        if (dn.equals(AOSP_TEST_KEY_DN, ignoreCase = true)) return true

        // Also match when the DN has the same components but different order
        // or extra attributes, as long as O=Android AND CN=Android are present.
        val parts = dn.split(",").map { it.trim() }
        val hasAndroidOrg = parts.any { it.equals("O=Android", ignoreCase = true) }
        val hasAndroidCn = parts.any { it.equals("CN=Android", ignoreCase = true) }
        return hasAndroidOrg && hasAndroidCn
    }
}
