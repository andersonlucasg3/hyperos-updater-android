package com.hyperos.updater.domain.installer

import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuApkInstaller @Inject constructor() : ApkInstaller {

    private val isAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

    override suspend fun install(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        if (!isAvailable) return InstallResult.ShizukuNotAvailable

        return try {
            val cmd = if (isSystemApp) {
                arrayOf("pm", "install", "-r", "-d", "--user", "0", apkFile.absolutePath)
            } else {
                arrayOf("pm", "install", "-r", apkFile.absolutePath)
            }
            // Shizuku 13+ made newProcess private; use reflection as fallback
            val process = try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                method.isAccessible = true
                method.invoke(null, cmd, null, null) as? java.lang.Process
            } catch (_: Exception) {
                null
            }

            if (process != null) {
                process.waitFor()
                if (process.exitValue() == 0) InstallResult.Success
                else InstallResult.Failure("pm install exited with code ${process.exitValue()}")
            } else {
                InstallResult.ShizukuNotAvailable
            }
        } catch (e: Exception) {
            InstallResult.Failure(e.message ?: "Shizuku install failed")
        }
    }
}
