package com.hyperos.updater.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.hyperos.updater.ui.components.DownloadProgress
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import com.hyperos.updater.util.ShizukuHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveDownload(
    val key: String,
    val appName: String,
    val fileName: String,
    val progress: DownloadProgress = DownloadProgress(status = DownloadStatus.PREPARING)
)

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val app: Context,
    private val downloadUseCase: DownloadUpdateUseCase
) {
    private val _downloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ActiveDownload>> = _downloads

    private val activeJobs = mutableMapOf<String, Job>()
    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }

    fun startDownload(url: String, fileName: String, key: String, appName: String) {
        activeJobs[key]?.cancel()
        _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.PREPARING))) }

        activeJobs[key] = CoroutineScope(Dispatchers.IO).launch {
            try {
                var lastBytes = -1L
                var lastTime = 0L
                downloadUseCase.download(url, fileName, null).collect { p ->
                    val now = System.currentTimeMillis()
                    val speed = if (lastBytes >= 0 && lastTime > 0) {
                        val elapsed = (now - lastTime).coerceAtLeast(100)
                        ((p.bytesDownloaded - lastBytes) * 1000 / elapsed)
                    } else 0L
                    lastBytes = p.bytesDownloaded
                    lastTime = now

                    val progress = DownloadProgress(fileName = fileName, progress = p.progress,
                        bytesDownloaded = p.bytesDownloaded, totalBytes = p.totalBytes,
                        speedBytesPerSec = speed, status = DownloadStatus.DOWNLOADING)
                    _downloads.update { it + (key to ActiveDownload(key, appName, fileName, progress)) }
                }

                // Download complete — trigger install
                val file = File(downloadsDir, fileName)
                _downloads.update {
                    it + (key to ActiveDownload(key, appName, fileName,
                        DownloadProgress(fileName = fileName, status = DownloadStatus.INSTALLING)))
                }
                val ext = fileName.substringAfterLast('.', "apk")
                val error = when (ext) {
                    "apk" -> installApk(file)
                    "apkm", "xapk", "apks" -> installSplitApk(file)
                    "aab" -> "AAB cannot be installed directly"
                    else -> installApk(file)
                }
                _downloads.update {
                    it + (key to ActiveDownload(key, appName, fileName,
                        if (error == null) DownloadProgress(fileName = fileName, status = DownloadStatus.COMPLETED)
                        else DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR)))
                }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Download failed: $key", e)
                _downloads.update {
                    it + (key to ActiveDownload(key, appName, fileName,
                        DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR)))
                }
            }
        }
    }

    fun cancelDownload(key: String) {
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        _downloads.update {
            it + (key to (it[key]?.copy(progress = DownloadProgress(status = DownloadStatus.CANCELLED)) ?: return@update it))
        }
    }

    fun dismissDownload(key: String) {
        _downloads.update { it - key }
    }

    fun retryInstall(key: String) {
        val dl = _downloads.value[key] ?: return
        val file = File(downloadsDir, dl.fileName)
        if (file.exists() && file.length() > 0) {
            _downloads.update { it + (key to dl.copy(progress = DownloadProgress(fileName = dl.fileName, status = DownloadStatus.INSTALLING))) }
            Thread {
                val error = installApk(file)
                val newStatus = if (error == null) DownloadStatus.COMPLETED else DownloadStatus.ERROR
                _downloads.update { it + (key to dl.copy(progress = DownloadProgress(fileName = dl.fileName, status = newStatus))) }
            }.start()
        }
    }

    fun installApk(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "File not found or empty"
        try {
            Log.i("DownloadManager", "Installing: ${file.absolutePath} (${file.length()} bytes)")

            // Try Shizuku first for silent install via pm install
            if (ShizukuHelper.isReady()) {
                try {
                    // Pipe APK content via stdin to pm install — bypasses file permission issues
                    val method = Shizuku::class.java.declaredMethods.firstOrNull {
                        it.name == "newProcess" && it.parameterTypes.size == 3
                    }
                    if (method != null) {
                        method.isAccessible = true
                        val cmd = arrayOf("pm", "install", "-S", file.length().toString(), "-r", "-d")
                        val process = method.invoke(null, cmd, null, null) as? java.lang.Process
                        if (process != null) {
                            // Pipe the APK content to pm's stdin
                            val outputStream = process.outputStream
                            file.inputStream().use { input ->
                                input.copyTo(outputStream)
                            }
                            outputStream.close()
                            val exitCode = process.waitFor()
                            val err = process.errorStream.bufferedReader().readText()
                            Log.i("DownloadManager", "Shizuku pm exit=$exitCode err=$err")
                            if (exitCode == 0) return null // success!
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Shizuku failed: ${e.message}")
                }
            }

            // Fallback: open system package installer
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(intent)
            return null
        } catch (e: Exception) {
            return "Install error: ${e.message}"
        }
    }

    private fun installSplitApk(file: File): String? {
        val outDir = File(downloadsDir, file.nameWithoutExtension)
        outDir.mkdirs()
        try {
            val apkFiles = mutableListOf<File>()
            ZipFile(file).use { zip ->
                zip.entries().asIterator().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val out = File(outDir, entry.name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.extension == "apk") apkFiles.add(out)
                }
            }
            if (apkFiles.isEmpty()) {
                return "No APKs found in split bundle"
            }
            if (apkFiles.size == 1) {
                return installApk(apkFiles.first())
            }
            installMultipleApks(apkFiles)
            return null
        } catch (e: Exception) {
            Log.e("DownloadManager", "Split APK extract failed", e)
            return "Split APK extract failed: ${e.message}"
        }
    }

    private fun installMultipleApks(apkFiles: List<File>): String? {
        try {
            Log.i("DownloadManager", "Installing ${apkFiles.size} split APKs")

            if (ShizukuHelper.isReady()) {
                // Use pm install-create + install-write + install-commit via Shizuku
                return installSplitsViaShizuku(apkFiles)
            }

            // Fallback: install base APK via package installer
            val baseApk = apkFiles.find { it.name.equals("base.apk", ignoreCase = true) }
                ?: apkFiles.first()
            return installApk(baseApk)
        } catch (e: Exception) {
            Log.e("DownloadManager", "Split install failed", e)
            return "Split install failed: ${e.message}"
        }
    }

    private fun installSplitsViaShizuku(apkFiles: List<File>): String? {
        try {
            val method = Shizuku::class.java.declaredMethods.firstOrNull {
                it.name == "newProcess" && it.parameterTypes.size == 3
            } ?: return "Shizuku newProcess not available"
            method.isAccessible = true

            // Step 1: create session
            val createCmd = arrayOf("pm", "install-create", "-r", "-d")
            val createProcess = method.invoke(null, createCmd, null, null) as? java.lang.Process
                ?: return "Failed to create pm process"
            val sessionOutput = createProcess.inputStream.bufferedReader().readText()
            val sessionId = Regex("(\\d+)").find(sessionOutput)?.value?.toIntOrNull()
                ?: return "Failed to parse session ID from: $sessionOutput"
            Log.i("DownloadManager", "Session: $sessionId")

            // Step 2: write each APK
            var baseName: String? = null
            for (apk in apkFiles) {
                val size = apk.length()
                val name = if (apk.name.startsWith("base")) { baseName = apk.name; "base.apk" }
                else apk.name
                val writeCmd = arrayOf("pm", "install-write", "-S", size.toString(), sessionId.toString(), name, "-")
                val writeProcess = method.invoke(null, writeCmd, null, null) as? java.lang.Process
                    ?: return "Failed to write $name"
                apk.inputStream().use { it.copyTo(writeProcess.outputStream) }
                writeProcess.outputStream.close()
                writeProcess.waitFor()
            }

            // Step 3: commit
            val commitCmd = arrayOf("pm", "install-commit", sessionId.toString())
            val commitProcess = method.invoke(null, commitCmd, null, null) as? java.lang.Process
                ?: return "Failed to commit"
            val exitCode = commitProcess.waitFor()
            val err = commitProcess.errorStream.bufferedReader().readText()
            Log.i("DownloadManager", "Split install exit=$exitCode err=$err")
            return if (exitCode == 0) null else "Split install failed: $err"
        } catch (e: Exception) {
            Log.e("DownloadManager", "Shizuku split install error", e)
            return "Shizuku split install error: ${e.message}"
        }
    }
}
