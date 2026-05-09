package com.hyperos.updater.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.MessageDigest

fun File.md5(): String? {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(this).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        BigInteger(1, digest.digest()).toString(16).padStart(32, '0')
    } catch (e: Exception) {
        null
    }
}

fun Long.toHumanReadableSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${size.toLong()} ${units[unitIndex]}"
    else "%.1f %s".format(size, units[unitIndex])
}

fun Context.isPackageInstalled(packageName: String): Boolean {
    return try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
