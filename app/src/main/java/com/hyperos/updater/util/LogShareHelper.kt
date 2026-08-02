package com.hyperos.updater.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.hyperos.updater.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Collects logs for sharing: app/device info, logcat output, and crash files.
 *
 * Usage: call [collectLogs] from a coroutine (it switches to [Dispatchers.IO]).
 */
object LogShareHelper {

    private const val TAG = "LogShareHelper"

    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Collects all diagnostic logs into a single file under
     * `context.cacheDir/share/hyperos-logs-<timestamp>.txt`.
     *
     * @param context        Application or Activity context.
     * @param rootAvailable  Whether root (su) is available — if true, a full
     *                       system logcat is appended after the app-scoped one.
     * @return The written file, ready to be shared via FileProvider.
     */
    suspend fun collectLogs(context: Context, rootAvailable: Boolean): File =
        withContext(Dispatchers.IO) {
            val shareDir = File(context.cacheDir, "share")
            shareDir.mkdirs()
            val timestamp = dateFormat.format(Date())
            val file = File(shareDir, "hyperos-logs-$timestamp.txt")

            val sb = StringBuilder()

            // ── Header ──────────────────────────────────────────────────────
            sb.appendLine("=== HyperOS Updater Logs ===")
            sb.appendLine("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_TIME})")
            sb.appendLine("Device: ${Build.MODEL} (${Build.DEVICE})")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("Root: $rootAvailable")
            sb.appendLine("Generated: $timestamp")
            sb.appendLine()

            // ── App logcat (self-UID only, no root needed) ──────────────────
            sb.appendLine("=== App Logcat (last 2000 lines) ===")
            sb.appendLine()
            val appLogcat = runLogcat(arrayOf("logcat", "-d", "-t", "2000", "-v", "threadtime"))
            sb.append(appLogcat)
            sb.appendLine()

            // ── Full system logcat (root only) ──────────────────────────────
            if (rootAvailable) {
                sb.appendLine("=== System Logcat (root, last 3000 lines) ===")
                sb.appendLine()
                val sysLogcat = runLogcat(arrayOf("su", "-c", "logcat -d -t 3000 -v threadtime"))
                sb.append(sysLogcat)
                sb.appendLine()
            }

            // ── Crash files (newest 3) ──────────────────────────────────────
            val crashDir = File(context.filesDir, "crash")
            if (crashDir.exists()) {
                val crashFiles = crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(3)
                    ?: emptyList()
                if (crashFiles.isNotEmpty()) {
                    sb.appendLine("=== Recent Crashes (${crashFiles.size}) ===")
                    sb.appendLine()
                    crashFiles.forEach { cf ->
                        sb.appendLine("--- ${cf.name} ---")
                        try {
                            sb.appendLine(cf.readText())
                        } catch (e: Exception) {
                            sb.appendLine("(could not read: ${e.message})")
                        }
                        sb.appendLine()
                    }
                }
            }

            file.writeText(sb.toString())
            Log.i(TAG, "Logs written to ${file.absolutePath} (${file.length()} bytes)")
            file
        }

    /**
     * Runs a logcat command with the waitFor-before-join pipe discipline.
     * Returns stdout as a String (truncated to ~2 MB to avoid OOM).
     */
    private fun runLogcat(cmd: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            var stdout = ""
            var stderr = ""
            val stdoutThread = thread {
                stdout = process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrThread = thread {
                stderr = process.errorStream.bufferedReader().use { it.readText() }
            }

            // waitFor BEFORE joining reader threads — readers block until EOF
            // (process exit), so joining first would hang forever on a stuck process.
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            stdoutThread.join(5_000)
            stderrThread.join(5_000)

            // Truncate to prevent OOM when preparing the share intent
            val maxLen = 2_000_000
            if (stdout.length > maxLen) {
                stdout = stdout.take(maxLen) + "\n\n... (truncated after $maxLen chars)"
            }
            stdout
        } catch (e: Exception) {
            "(logcat failed: ${e.message})"
        }
    }
}
