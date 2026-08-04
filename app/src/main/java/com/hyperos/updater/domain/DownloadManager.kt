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
import com.hyperos.updater.domain.installer.PackageManagerInstaller
import com.hyperos.updater.domain.installer.RootApkInstaller
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import com.hyperos.updater.util.WearOsDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

data class ActiveDownload(
    val key: String,
    val appName: String,
    val fileName: String,
    val progress: DownloadProgress = DownloadProgress(status = DownloadStatus.PREPARING)
)

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val app: Context,
    private val downloadUseCase: DownloadUpdateUseCase,
    private val rootInstaller: RootApkInstaller,
    private val pkgInstaller: PackageManagerInstaller
) {
    /** Public directory where all downloads land — also used by the auto-update worker. */
    val downloadsDirectory: File get() = downloadsDir
    private val _downloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ActiveDownload>> = _downloads

    private val activeJobs = mutableMapOf<String, Job>()
    private val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HyperOSUpdater").also { it.mkdirs() }
    private val fileCache = mutableMapOf<String, String>()

    // ── Public API ──────────────────────────────────────────────

    /** Check if a previously downloaded APK exists for [key]. If so, install directly. Returns true if cached. */
    fun installCached(key: String, appName: String): Boolean {
        fun validApk(f: File) = f.exists() && f.length() > 0L &&
                app.packageManager.getPackageArchiveInfo(f.absolutePath, 0) != null

        // 1. Explicit fileCache (set only after COMPLETED/AWAITING installs)
        fileCache[key]?.let { fileName ->
            val file = File(downloadsDir, fileName)
            if (validApk(file)) {
                runInstall(file, key, appName, fileName)
                return true
            }
            fileCache.remove(key)
        }
        // 2. Active download entries — only ones that actually finished downloading
        _downloads.value[key]?.let { dl ->
            if (dl.progress.status == DownloadStatus.COMPLETED || dl.progress.status == DownloadStatus.AWAITING_INSTALL) {
                val file = File(downloadsDir, dl.fileName)
                if (validApk(file)) {
                    fileCache[key] = dl.fileName
                    runInstall(file, key, appName, dl.fileName)
                    return true
                }
            }
        }
        return false
    }

    fun startDownload(url: String, fileName: String, key: String, appName: String, headers: Map<String, String> = emptyMap(), skipInstall: Boolean = false) {
        activeJobs[key]?.cancel()
        _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.PREPARING))) }

        activeJobs[key] = CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(downloadsDir, fileName)

                // Only reuse the cache if it's a valid APK — garbage from failed
                // attempts (HTML error pages, partial downloads) must be re-downloaded
                val cachedValid = file.exists() && file.length() > 0 &&
                    app.packageManager.getPackageArchiveInfo(file.absolutePath, 0) != null
                if (cachedValid) {
                    Log.i("DownloadManager", "APK cached: ${file.absolutePath} (${file.length()} bytes), skipping download")
                } else {
                    if (file.exists()) file.delete()
                    var lastBytes = -1L; var lastTime = 0L
                    downloadUseCase.download(url, fileName, null, downloadsDir, headers).collect { p ->
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

                // CDN URLs often lack an extension — detect bundles by content and fix
                // the file type before dispatching to the install flow
                val finalFile = adjustArchiveType(file)

                // Wear OS guard: scan manifest for android.hardware.type.watch
                // Blocks install of watch APKs on phones
                if (isWearOsApk(finalFile)) {
                    Log.w("DownloadManager", "Wear OS APK detected, blocking install: ${finalFile.name}")
                    _downloads.update { it + (key to ActiveDownload(key, appName, finalFile.name,
                        DownloadProgress(fileName = finalFile.name, status = DownloadStatus.ERROR,
                            errorMessage = "Este APK é para Wear OS (relógio), não para o telefone",
                            canUseSystemInstaller = false))) }
                    return@launch
                }

                if (skipInstall) {
                    // Worker pre-download — leave in AWAITING_INSTALL so the user can tap to install
                    fileCache[key] = finalFile.name
                    _downloads.update { it + (key to ActiveDownload(key, appName, finalFile.name,
                        DownloadProgress(fileName = finalFile.name, status = DownloadStatus.AWAITING_INSTALL))) }
                } else {
                    runInstall(finalFile, key, appName, finalFile.name)
                }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Download failed: $key", e)
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR, errorMessage = e.message ?: e.javaClass.simpleName, canUseSystemInstaller = false))) }
            }
        }
    }

    fun cancelDownload(key: String) {
        activeJobs.remove("$key-poll")?.cancel()
        activeJobs[key]?.cancel(); activeJobs.remove(key)
        _downloads.update { it + (key to (it[key]?.copy(progress = DownloadProgress(status = DownloadStatus.CANCELLED)) ?: return@update it)) }
    }

    fun dismissDownload(key: String) {
        activeJobs.remove("$key-poll")?.cancel()
        activeJobs[key]?.cancel()
        _downloads.update { it - key }
    }

    fun retryInstall(key: String) {
        val dl = _downloads.value[key] ?: return
        val file = File(downloadsDir, dl.fileName)
        if (file.exists() && file.length() > 0) {
            runInstall(file, key, dl.appName, dl.fileName)
        }
    }

    /**
     * Registers a file that was already downloaded (e.g. by the auto-update worker)
     * so it appears in the Downloads tab as AWAITING_INSTALL.
     */
    fun registerCompletedDownload(key: String, appName: String, fileName: String) {
        val file = File(downloadsDir, fileName)
        if (file.exists() && file.length() > 0) {
            fileCache[key] = fileName
            _downloads.update { it + (key to ActiveDownload(key, appName, fileName,
                DownloadProgress(fileName = fileName, status = DownloadStatus.AWAITING_INSTALL))) }
            Log.i("DownloadManager", "Registered completed download: $fileName (key=$key)")
        } else {
            Log.w("DownloadManager", "registerCompletedDownload: file not found $fileName")
        }
    }

    /**
     * Opens the downloaded file via the system package installer (ACTION_VIEW).
     * Works for APKs and split bundles (.xapk/.apkm/.apks) — bundles use
     * application/octet-stream so SAI-style installers can handle them.
     */
    fun openSystemInstaller(key: String) {
        val dl = _downloads.value[key] ?: return
        val file = File(downloadsDir, dl.fileName)
        if (!file.exists() || file.length() == 0L) {
            Log.w("DownloadManager", "openSystemInstaller: file not found for $key")
            return
        }
        pkgInstaller.openInstallIntent(file)
    }

    // ── Unified install entry point ─────────────────────────────

    /** Single entry point for all install flows. Runs on Dispatchers.IO, handles status updates, caching, and polling. */
    private fun runInstall(file: File, key: String, appName: String, fileName: String) {
        _downloads.update { it + (key to ActiveDownload(key, appName, fileName, DownloadProgress(fileName = fileName, status = DownloadStatus.INSTALLING))) }
        activeJobs.remove("$key-poll")?.cancel()
        activeJobs[key]?.cancel()
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
                // All installs are delegated to the system installer — any archive
                // type can be retried via the system-installer button on error.
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName,
                    DownloadProgress(fileName = fileName, status = finalStatus,
                        errorMessage = if (finalStatus == DownloadStatus.ERROR) result else null,
                        canUseSystemInstaller = finalStatus == DownloadStatus.ERROR && file.exists()))) }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Install failed: $key", e)
                _downloads.update { it + (key to ActiveDownload(key, appName, fileName,
                    DownloadProgress(fileName = fileName, status = DownloadStatus.ERROR,
                        errorMessage = e.message ?: e.javaClass.simpleName,
                        canUseSystemInstaller = file.exists()))) }
            }
        }
    }

    /**
     * Detects the real archive type by content and renames the file if needed.
     * CDN URLs often carry no extension, so a downloaded XAPK/APKM bundle may arrive
     * named `.apk` — installing that as a single APK produces a corrupted install.
     * Rule: a bundle is a ZIP containing inner `.apk` entries and NO root
     * `AndroidManifest.xml` (a real single APK always has the manifest at root).
     */
    private fun adjustArchiveType(file: File): File {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext != "apk") return file // already correctly typed (apkm/xapk/apks/aab)
        val isBundle = try {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.take(500).toList()
                val hasManifestAtRoot = names.any { it.equals("AndroidManifest.xml", ignoreCase = true) }
                val innerApks = names.count { it.lowercase().endsWith(".apk") }
                !hasManifestAtRoot && innerApks >= 1
            }
        } catch (e: Exception) {
            Log.w("DownloadManager", "adjustArchiveType: cannot read ${file.name} as zip (${e.message}) — keeping .apk")
            false
        }
        if (!isBundle) return file
        val renamed = File(file.parentFile, file.nameWithoutExtension + ".xapk")
        return if (file.renameTo(renamed)) {
            Log.i("DownloadManager", "Detected split-APK bundle by content: ${file.name} → ${renamed.name}")
            renamed
        } else {
            Log.w("DownloadManager", "Bundle detected but rename failed: ${file.name}")
            file
        }
    }

    /**
     * Checks whether the downloaded file is a Wear OS APK by inspecting its manifest.
     *
     * 1. Preferred: [PackageManager.getPackageArchiveInfo] → [PackageInfo.reqFeatures]
     *    → any [FeatureInfo.name] == "android.hardware.type.watch".
     * 2. Fallback: zip-based byte scan of AndroidManifest.xml (via [WearOsDetector]).
     *
     * For split bundles (XAPK/APKM with no root manifest), inner .apk entries are
     * scanned recursively.
     *
     * Fail-soft: returns `false` if the manifest cannot be read, so legitimate apps
     * are never blocked by a read error.
     */
    private fun isWearOsApk(file: File): Boolean {
        // 1. Preferred: PackageManager
        try {
            val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info?.reqFeatures?.forEach { feature ->
                if (feature.name == "android.hardware.type.watch") {
                    Log.i("DownloadManager", "Wear OS detected via PackageManager for ${file.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d("DownloadManager", "PackageManager Wear check failed: ${e.message}")
        }

        // 2. Fallback: zip-based manifest scan (handles bundles too)
        if (WearOsDetector.scanApkForWearFeature(file)) {
            Log.i("DownloadManager", "Wear OS detected via manifest scan for ${file.name}")
            return true
        }

        return false
    }

    /** Delegates all installs to the system installer via ACTION_VIEW. */
    private fun executeInstall(file: File): String? {
        val ext = file.name.substringAfterLast('.', "apk")
        if (ext == "aab") return "AAB cannot be installed directly"
        if (!file.exists() || file.length() == 0L) return "File not found or empty"
        return if (pkgInstaller.openInstallIntent(file)) "awaiting_user"
        else "Failed to open system installer"
    }

    // ── Install methods ─────────────────────────────────────────

    /** Delegates the APK to the system installer. Public entry point kept for backward compat. */
    fun installApk(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "File not found or empty"
        return if (pkgInstaller.openInstallIntent(file)) "awaiting_user"
        else "Failed to open system installer"
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


    // ── Split APK — delegated to system installer ──────────────
    // No more extraction/session/root chain — the bundle file is handed off
    // directly to the system's default installer (e.g. SAI) via ACTION_VIEW
    // with application/octet-stream MIME type.

    /** Delegates the bundle to a SAI-style system installer. */
    private fun installSplitApk(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "File not found or empty"
        return if (pkgInstaller.openInstallIntent(file)) "awaiting_user"
        else "Failed to open system installer"
    }

    // ── DEPRECATED: Root/session install methods ──────────────────
    // These are kept on disk but removed from the dispatch chain.
    // Root is still used for full-logcat capture in LogShareHelper.
    // They may return if a future version re-introduces privileged install paths.

    @Deprecated("Install chain removed — kept for reference only", level = DeprecationLevel.WARNING)
    private fun rootInstallSingle(file: File): String? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val writerThread = thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write("pm install -S ${file.length()} -r -d -i com.android.vending")
                        writer.newLine()
                        writer.flush()
                        file.inputStream().use { it.copyTo(process.outputStream) }
                    }
                } catch (_: Exception) {}
            }
            var stdout = ""
            var stderr = ""
            val stdoutThread = thread { stdout = process.inputStream.bufferedReader().use { it.readText() } }
            val stderrThread = thread { stderr = process.errorStream.bufferedReader().use { it.readText() } }
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e("DownloadManager", "Root pm install timed out after 120s")
                return "Root: timed out"
            }
            stdoutThread.join(5_000)
            stderrThread.join(5_000)
            writerThread.join(5_000)
            val exitCode = process.exitValue()
            val output = stdout + stderr
            Log.i("DownloadManager", "Root pm exit=$exitCode output=${output.take(200)}")
            if (exitCode == 0 || output.contains("Success")) null
            else "Root: $output"
        } catch (e: Exception) {
            "Root install error: ${e.message}"
        }
    }

    @Deprecated("Install chain removed — kept for reference only", level = DeprecationLevel.WARNING)
    private fun rootInstallMulti(apkFiles: List<File>): String? {
        apkFiles.forEach { apk ->
            val result = rootInstallSingle(apk)
            if (result != null) return "Root multi: ${apk.name} failed: $result"
        }
        return null
    }

    @Deprecated("Install chain removed — kept for reference only", level = DeprecationLevel.WARNING)
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
            val statusIntent = Intent("com.hyperos.updater.INSTALL_DONE").apply {
                setPackage(app.packageName)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                app, sessionId, statusIntent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i("DownloadManager", "Session install $sessionId")
            null
        } catch (e: Exception) { e.message }
    }

    @Deprecated("Install chain removed — kept for reference only", level = DeprecationLevel.WARNING)
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
            val statusIntent = Intent("com.hyperos.updater.INSTALL_DONE").apply {
                setPackage(app.packageName)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                app, sessionId, statusIntent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pendingIntent.intentSender); session.close()
            Log.i("DownloadManager", "Multi-session $sessionId: ${apkFiles.size} splits")
            null
        } catch (e: Exception) { e.message }
    }
}
