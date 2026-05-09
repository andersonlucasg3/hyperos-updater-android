package com.hyperos.updater.data.repository

import android.os.Build
import android.util.Log
import com.hyperos.updater.domain.model.DeviceInfo
import com.hyperos.updater.domain.repository.DeviceRepository
import javax.inject.Inject
import javax.inject.Singleton
import java.io.BufferedReader
import java.io.InputStreamReader

@Singleton
class DeviceRepositoryImpl @Inject constructor() : DeviceRepository {

    override suspend fun getDeviceInfo(): DeviceInfo {
        val codename = Build.DEVICE?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: getProp("ro.product.device")
            ?: "popsicle"

        val marketingName = Build.MODEL?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: getProp("ro.product.marketname")
            ?: getProp("ro.product.model")
            ?: "Xiaomi"

        // HyperOS/MIUI version: try multiple known property names
        val miuiVersion = getProp("persist.sys.grant_version")
            ?: getProp("ro.miui.ui.version.name")
            ?: Build.DISPLAY?.takeIf { it.contains("OS") || it.contains("V") }
            ?: Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() }
            ?: "unknown"

        val androidVersion = Build.VERSION.RELEASE?.takeIf { it != "6.0.1" }
            ?: "16"

        val androidSdk = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.SDK_INT else 36

        val region = when {
            miuiVersion.endsWith("CNXM") -> "CN"
            miuiVersion.endsWith("MIXM") -> "GL"
            miuiVersion.endsWith("EUXM") -> "EU"
            miuiVersion.endsWith("INXM") -> "IN"
            miuiVersion.endsWith("TWXM") -> "TW"
            else -> "CN"
        }

        Log.d("DeviceRepo", "codename=$codename version=$miuiVersion region=$region sdk=$androidSdk")

        return DeviceInfo(
            codename = codename,
            marketingName = marketingName,
            miuiVersion = miuiVersion,
            androidVersion = androidVersion,
            androidSdk = androidSdk,
            region = region,
            isGlobal = region != "CN"
        )
    }

    private fun getProp(prop: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine()?.trim() }
        } catch (e: Exception) {
            null
        }
    }
}
