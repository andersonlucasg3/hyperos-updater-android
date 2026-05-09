package com.hyperos.updater.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    READY,
    CHECKING
}

object ShizukuHelper {

    private var binderReceived = false
    private var listenersAdded = false

    fun init() {
        if (listenersAdded) return
        Log.i("ShizukuHelper", "init() called — adding listeners")
        try {
            Shizuku.addBinderReceivedListener {
                Log.i("ShizukuHelper", "Binder RECEIVED")
                binderReceived = true
            }
            Shizuku.addBinderDeadListener {
                Log.i("ShizukuHelper", "Binder DEAD")
                binderReceived = false
            }
            Shizuku.addRequestPermissionResultListener { code, result ->
                Log.i("ShizukuHelper", "Permission result: code=$code result=$result")
                if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    binderReceived = true
                }
            }
            listenersAdded = true
            Log.i("ShizukuHelper", "Listeners added successfully")
        } catch (e: Exception) {
            Log.w("ShizukuHelper", "Failed to add listeners: ${e.message}", e)
        }
    }

    fun checkState(): ShizukuState {
        init()
        return try {
            val permGranted = hasPermission()
            val binderAlive = try { Shizuku.pingBinder() } catch (_: Exception) { false }
            Log.i("ShizukuHelper", "checkState: binderAlive=$binderAlive binderRecv=$binderReceived perm=$permGranted")

            when {
                !binderAlive && !binderReceived -> ShizukuState.INSTALLED_NOT_RUNNING
                !permGranted -> ShizukuState.RUNNING_NO_PERMISSION
                else -> { binderReceived = true; ShizukuState.READY }
            }
        } catch (e: Exception) {
            Log.w("ShizukuHelper", "Check failed: ${e.message}", e)
            ShizukuState.NOT_INSTALLED
        }
    }

    fun isReady(): Boolean = checkState() == ShizukuState.READY

    private fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) { }
    }

    fun openShizukuPlayStore(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun getSetupInstructions(state: ShizukuState): String {
        return when (state) {
            ShizukuState.NOT_INSTALLED ->
                "Shizuku is not installed.\n\n" +
                "1. Download Shizuku from shizuku.rikka.app\n" +
                "2. Install and open the app\n" +
                "3. Start it via wireless debugging\n" +
                "4. Grant permission to HyperOS Updater"
            ShizukuState.INSTALLED_NOT_RUNNING ->
                "Shizuku is installed but not running.\n\n" +
                "1. Open the Shizuku app\n" +
                "2. Tap 'Start' (via wireless debugging)\n" +
                "3. Return here — the binder connects automatically"
            ShizukuState.RUNNING_NO_PERMISSION ->
                "Shizuku is running but needs permission.\n\n" +
                "Tap 'Grant Permission' below."
            ShizukuState.READY ->
                "Shizuku is connected and ready.\n\n" +
                "Split APK install will work."
            ShizukuState.CHECKING -> "Checking..."
        }
    }
}
