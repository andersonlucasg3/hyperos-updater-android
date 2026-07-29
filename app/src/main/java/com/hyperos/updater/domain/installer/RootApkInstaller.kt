package com.hyperos.updater.domain.installer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class RootApkInstaller @Inject constructor() : ApkInstaller {

    /** Result of a root availability probe, with per-candidate diagnostics for the UI. */
    data class RootDiagnosis(
        val available: Boolean,
        val detail: String
    )

    @Volatile
    private var availabilityChecked = false
    @Volatile
    private var isAvailable = false

    fun resetAvailability() {
        availabilityChecked = false
        isAvailable = false
    }

    /** Candidate su locations, most common first. */
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su"
    )

    /**
     * Probes each su candidate and returns diagnostics. The FIRST candidate gets
     * [promptTimeoutSeconds] (enough for the user to answer a Magisk/KernelSU grant
     * dialog); the rest are quick probes with [probeTimeoutSeconds].
     */
    suspend fun diagnoseAvailability(
        promptTimeoutSeconds: Long = 60,
        probeTimeoutSeconds: Long = 8
    ): RootDiagnosis = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var available = false
        suCandidates.forEachIndexed { index, su ->
            if (available) return@forEachIndexed
            val timeout = if (index == 0) promptTimeoutSeconds else probeTimeoutSeconds
            val result = probeSu(su, timeout)
            lines += result
            if (result.startsWith("OK")) available = true
        }
        isAvailable = available
        availabilityChecked = true
        Log.i("RootApkInstaller", "diagnose: ${lines.joinToString()}")
        RootDiagnosis(available, lines.joinToString("\n"))
    }

    /** Runs `su -c echo ok`; returns "OK <su>" or a failure description. Never hangs. */
    private fun probeSu(su: String, timeoutSeconds: Long): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "echo ok"))
            // waitFor BEFORE reading: reader threads would block on a grant prompt.
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "$su: timeout (${timeoutSeconds}s)"
            } else {
                val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
                val code = process.exitValue()
                when {
                    out.contains("ok") -> "OK $su"
                    err.isNotBlank() -> "$su: exit=$code err=${err.take(80)}"
                    else -> "$su: exit=$code out=${out.take(80)}"
                }
            }
        } catch (e: Exception) {
            "$su: ${e.javaClass.simpleName}: ${e.message?.take(60)}"
        }
    }

    suspend fun checkAvailability(): Boolean {
        if (!availabilityChecked) {
            diagnoseAvailability(promptTimeoutSeconds = 10, probeTimeoutSeconds = 5)
        }
        return isAvailable
    }

    override suspend fun install(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        if (!checkAvailability()) return InstallResult.RootNotAvailable

        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("su")

                // Write command + APK data on a separate thread to avoid deadlock
                val writerThread = thread {
                    try {
                        process.outputStream.bufferedWriter().use { writer ->
                            writer.write("pm install -S ${apkFile.length()} -r -d -i com.android.vending")
                            writer.newLine()
                            writer.flush()
                            apkFile.inputStream().use { it.copyTo(process.outputStream) }
                        }
                    } catch (_: Exception) {}
                }

                // Read stdout and stderr on separate threads
                var stdout = ""
                var stderr = ""
                val stdoutThread = thread { stdout = process.inputStream.bufferedReader().use { it.readText() } }
                val stderrThread = thread { stderr = process.errorStream.bufferedReader().use { it.readText() } }

                // Wait for exit BEFORE joining readers: they block until EOF (process exit),
                // so joining first would make the timeout useless if the process hangs.
                val finished = process.waitFor(120, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    Log.e("RootApkInstaller", "su pm install timed out after 120s")
                    return@withContext InstallResult.Failure("Root install timed out")
                }
                stdoutThread.join(5_000)
                stderrThread.join(5_000)
                writerThread.join(5_000)
                val exitCode = process.exitValue()
                val output = stdout + stderr

                when {
                    exitCode == 0 || output.contains("Success") -> InstallResult.Success
                    output.contains("Failure") -> {
                        val reason = output.substringAfter("Failure")
                            .trimStart('[', ' ')
                            .substringBefore(']')
                            .ifBlank { "Unknown" }
                        InstallResult.Failure(reason)
                    }
                    else -> InstallResult.Failure("pm exited with code $exitCode: ${output.take(200)}")
                }
            } catch (e: Exception) {
                InstallResult.Failure(e.message ?: "Root install failed")
            }
        }
    }
}
