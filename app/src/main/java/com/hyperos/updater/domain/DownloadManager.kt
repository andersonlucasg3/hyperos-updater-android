package com.hyperos.updater.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
    private val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HyperOSUpdater").also { it.mkdirs() }
    private val fileCache = mutableMapOf<String, String>()

    // ── Public API ──────────────────────────────────────────────

    /** Check if a previously downloaded APK exists for [key]. If so, install directly. Returns true if cached. */
    fun installCached(key: String, appName: String): Boolean {
        // 1. Explicit fileCache
        fileCache[key]?.let { fileName ->
            val file = File(downloadsDir, fileName)
            if (file.exists() && file.length() > 0L) {
                runInstall(file, key, appName, fileName)
                return true
            }
            fileCache.remove(key)
        }
        // 2. Active download entries with existing files
        _downloads.value[key]?.let { dl ->
            val file = File(downloadsDir, dl.fileName)
            if (file.exists() && file.length() > 0L) {
                fileCache[key] = dl.fileName
                runInstall(file, key, appName, dl.fileName)
                return true
            }
        }
        // 3. Scan downloads dir for orphaned APKs
        val apkFiles = downloadsDir.listFiles { f -> f.isFile && f.extension == "apk" } ?: emptyArray()
        when {
            apkFiles.size == 1 -> {
                val f = apkFiles.first()
                fileCache[key] = f.name
                runInstall(f, key, appName, f.name)
                return true
            }
            apkFiles.size > 1 -> {
                val keyParts = key.split(" ").filter { it.length > 3 }
                apkFiles.firstOrNull { f -> keyParts.any { p -> f.name.contains(p, ignoreCase = true) } }?.let { f ->
                    fileCache[key] = f.name
                    runInstall(f, key, appName, f.name)
                    return true
                }
            }
        }
        return false
    }

    fun startDownload(url: String, fileName: String, key: String, appName: String) {
        activeJobs[key]?.cancel()
        _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.PREPARING))) }

        activeJobs[key] = CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(downloadsDir, fileName)

                if (file.exists() && file.length() > 0) {
                    Log.i("DownloadManager", "APK cached: ${file.absolutePath} (${file.length()} bytes), skipping download")
                } else {
                    var lastBytes = -1L; var lastTime = 0L
                    downloadUseCase.download(url, fileName, null, downloadsDir).collect { p ->
                        val now = System.currentTimeMillis()
                        val speed = if (lastBytes >= 0 && lastTime > 0) {
                            ((p.bytesDownloaded - lastBytes) * 1000 / (now - lastTime).coerceAtLeast(100))
                        } else 0L
                        lastBytes = p.bytesDownloaded; lastTime = now
                        _downloads.update { it + (key to ActiveDownload(key, appName, fileName,
                            DownloadProgress(fileName = fileName, progress = p.progress, bytesDownloaded = p.bytesDownloaded, totalBytes = p.totalBytes, speedBytesPerSec = speed, status = DownloadStatus.DOWNLOADING))) }
                    }
                    Log.i("DownloadManager", "Download complete: ${file.absolutePath} (${file.length()} bytes)")
                }

                runInstall(file, key, appName, fileName)
            } catch (e: Exception) {
                Log.e("DownloadManager", "Download failed: $key", e)
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR))) }
            }
        }
    }

    fun cancelDownload(key: String) {
        activeJobs[key]?.cancel(); activeJobs.remove(key)
        activeJobs.remove("$key-poll")
        _downloads.update { it + (key to (it[key]?.copy(progress = DownloadProgress(status = DownloadStatus.CANCELLED)) ?: return@update it)) }
    }

    fun dismissDownload(key: String) {
        activeJobs.remove("$key-poll")
        _downloads.update { it - key }
    }

    fun retryInstall(key: String) {
        val dl = _downloads.value[key] ?: return
        val file = File(downloadsDir, dl.fileName)
        if (file.exists() && file.length() > 0) {
            runInstall(file, key, dl.appName, dl.fileName)
        }
    }

    // ── Unified install entry point ─────────────────────────────

    /** Single entry point for all install flows. Runs on Dispatchers.IO, handles status updates, caching, and polling. */
    private fun runInstall(file: File, key: String, appName: String, fileName: String) {
        _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.INSTALLING))) }
        activeJobs[key] = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = executeInstall(file)
                val finalStatus = when {
                    result == null -> DownloadStatus.COMPLETED
                    result == "awaiting_user" -> DownloadStatus.AWAITING_INSTALL
                    else -> DownloadStatus.ERROR
                }
                Log.i("DownloadManager", "Install result: $result → status=$finalStatus")
                if (finalStatus == DownloadStatus.COMPLETED || finalStatus == DownloadStatus.AWAITING_INSTALL) {
                    fileCache[key] = fileName
                }
                if (finalStatus == DownloadStatus.AWAITING_INSTALL) {
                    scheduleInstallPoll(file, key, appName, fileName)
                }
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = finalStatus))) }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Install failed: $key", e)
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR))) }
            }
        }
    }

    /** Dispatches to the right install method based on file extension. */
    private fun executeInstall(file: File): String? {
        val ext = file.name.substringAfterLast('.', "apk")
        return when (ext) {
            "apk" -> installApk(file)
            "apkm", "xapk", "apks" -> installSplitApk(file)
            "aab" -> "AAB cannot be installed directly"
            else -> installApk(file)
        }
    }

    // ── Install methods ─────────────────────────────────────────

    fun installApk(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "File not found or empty"
        try {
            Log.i("DownloadManager", "Installing: ${file.absolutePath} (${file.length()} bytes)")

            // 1. Unattended install via PackageInstaller.Session — best effort, may silently fail
            val sessionResult = sessionInstallSingle(file)
            if (sessionResult == null) {
                Log.i("DownloadManager", "Session commit OK, falling through for confirmed install")
            } else if (sessionResult != "unsupported") {
                Log.w("DownloadManager", "Session failed: $sessionResult")
            }

            // 2. Shizuku via pm install (stdin pipe)
            if (ShizukuHelper.isReady()) {
                val method = Shizuku::class.java.declaredMethods.firstOrNull { it.name == "newProcess" && it.parameterTypes.size == 3 }
                if (method != null) {
                    method.isAccessible = true
                    val cmd = arrayOf("pm", "install", "-S", file.length().toString(), "-r", "-d")
                    val process = method.invoke(null, cmd, null, null) as? java.lang.Process
                    if (process != null) {
                        file.inputStream().use { it.copyTo(process.outputStream) }
                        process.outputStream.close()
                        val exitCode = process.waitFor()
                        Log.i("DownloadManager", "Shizuku pm exit=$exitCode")
                        if (exitCode == 0) return null
                    }
                }
            }

            // 3. Fallback: system package installer — user must confirm
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(intent)
            Log.i("DownloadManager", "PackageInstaller opened for ${file.name}")
            return "awaiting_user"
        } catch (e: Exception) { return "Install error: ${e.message}" }
    }

    // ── Install confirmation polling ────────────────────────────

    private fun scheduleInstallPoll(file: File, key: String, appName: String, fileName: String) {
        val pkgInfo = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: run {
            Log.w("DownloadManager", "Cannot read package info from APK")
            return
        }
        val pkgName = pkgInfo.packageName
        val expectedVersion = pkgInfo.versionCode
        Log.i("DownloadManager", "Polling for install confirmation: $pkgName v$expectedVersion")

        val pollKey = "$key-poll"
        activeJobs[pollKey] = CoroutineScope(Dispatchers.IO).launch {
            for (i in 0 until 30) {
                delay(2000)
                try {
                    val installed = app.packageManager.getPackageInfo(pkgName, 0)
                    if (installed.versionCode >= expectedVersion) {
                        Log.i("DownloadManager", "Install confirmed: $pkgName v${installed.versionCode}")
                        _downloads.update { it + (key to ActiveDownload(key, appName, fileName,
                            DownloadProgress(fileName = fileName, status = DownloadStatus.COMPLETED))) }
                        fileCache[key] = fileName
                        return@launch
                    }
                } catch (_: PackageManager.NameNotFoundException) { /* fresh install in progress */ }
            }
            Log.w("DownloadManager", "Install poll timeout for $pkgName")
        }
    }

    // ── PackageInstaller.Session (unattended) ────────────────────

    private fun sessionInstallSingle(file: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return "unsupported"
        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            val dummyIntent = Intent("com.hyperos.updater.INSTALL_DONE")
            val pendingIntent = android.app.PendingIntent.getBroadcast(app, sessionId, dummyIntent, android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i("DownloadManager", "Session install $sessionId")
            null
        } catch (e: Exception) { e.message }
    }

    private fun sessionInstallMulti(apkFiles: List<File>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return installApk(apkFiles.first())
        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            apkFiles.forEach { apk ->
                val name = if (apk.name == "base.apk") "base.apk" else apk.name
                session.openWrite(name, 0, apk.length()).use { out ->
                    apk.inputStream().use { out.write(it.readBytes()) }
                }
            }
            val dummyIntent = Intent("com.hyperos.updater.INSTALL_DONE")
            val pendingIntent = android.app.PendingIntent.getBroadcast(app, sessionId, dummyIntent, android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pendingIntent.intentSender); session.close()
            Log.i("DownloadManager", "Multi-session $sessionId: ${apkFiles.size} splits")
            null
        } catch (e: Exception) { e.message }
    }

    // ── Split APK extraction ────────────────────────────────────

    private fun installSplitApk(file: File): String? {
        val outDir = File(downloadsDir, file.nameWithoutExtension); outDir.mkdirs()
        try {
            val apkFiles = mutableListOf<File>()
            ZipFile(file).use { zip ->
                zip.entries().asIterator().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val out = File(outDir, entry.name); out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                    if (out.extension == "apk") apkFiles.add(out)
                }
            }
            if (apkFiles.isEmpty()) return "No APKs found in split bundle"
            if (apkFiles.size == 1) return installApk(apkFiles.first())
            return sessionInstallMulti(apkFiles)
        } catch (e: Exception) { return "Split APK extract failed: ${e.message}" }
    }
}
