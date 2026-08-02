package com.hyperos.updater.util

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and writes them to timestamped crash files
 * before delegating to the previous handler. Keeps only the newest 10 files.
 *
 * Install with [install] — call it FIRST from [Application.onCreate].
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val MAX_CRASH_FILES = 10

    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun install(app: Application) {
        val crashDir = File(app.filesDir, "crash")
        pruneOldCrashes(crashDir)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousHandlerOrNull = if (previousHandler == null) null
            else if (previousHandler.javaClass.name.contains("CrashLogger")) previousHandler
            else previousHandler

        // Don't double-wrap
        if (previousHandlerOrNull != null &&
            previousHandlerOrNull.javaClass.name.contains("CrashLogger")
        ) return

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashDir.mkdirs()
                val timestamp = dateFormat.format(Date())
                val file = File(crashDir, "crash-$timestamp.txt")

                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("=== HyperOS Updater Crash Report ===")
                    pw.println("App: ${com.hyperos.updater.BuildConfig.VERSION_NAME} (build ${com.hyperos.updater.BuildConfig.BUILD_TIME})")
                    pw.println("Device: ${Build.MODEL} (${Build.DEVICE})")
                    pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("Thread: ${thread.name}")
                    pw.println("Timestamp: $timestamp")
                    pw.println()
                    pw.println("=== Stack Trace ===")
                    throwable.printStackTrace(pw)
                }
                file.writeText(sw.toString())

                Log.e(TAG, "Crash written to ${file.absolutePath}", throwable)
                pruneOldCrashes(crashDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            } finally {
                // Delegate to previous handler or kill the process
                if (previousHandlerOrNull != null &&
                    previousHandlerOrNull !== Thread.getDefaultUncaughtExceptionHandler()
                ) {
                    previousHandlerOrNull.uncaughtException(thread, throwable)
                } else {
                    // No previous handler — let the system handle it
                    @Suppress("CascadeIf")
                    if (throwable is ThreadDeath) throw throwable
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(10)
                }
            }
        }
    }

    private fun pruneOldCrashes(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_CRASH_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CRASH_FILES)
            .forEach { it.delete() }
    }
}
