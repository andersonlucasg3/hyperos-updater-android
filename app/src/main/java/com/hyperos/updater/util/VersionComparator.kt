package com.hyperos.updater.util

object VersionComparator {

    fun isNewer(currentVersion: String, newVersion: String): Boolean {
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

    private fun parseSemantic(version: String): List<Int> =
        version.split(".", "-").mapNotNull { it.toIntOrNull() }
}
