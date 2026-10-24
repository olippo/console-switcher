package com.example.consoleswitcher3.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val REQUEST_CODE_SHIZUKU = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            false
        }
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU) {
        if (!isShizukuAvailable()) return
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    fun getShizukuVersion(): Int {
        return try {
            if (isShizukuAvailable()) Shizuku.getVersion() else -1
        } catch (e: Throwable) {
            -1
        }
    }

    fun getShizukuUid(): Int {
        return try {
            if (isShizukuAvailable()) Shizuku.getUid() else -1
        } catch (e: Throwable) {
            -1
        }
    }

    /**
     * Obtains a privileged wrapped IBinder for the given system service name (e.g. "activity_task").
     */
    fun getSystemServiceBinder(serviceName: String): IBinder? {
        if (!hasShizukuPermission()) return null
        return try {
            val rawBinder = SystemServiceHelper.getSystemService(serviceName) ?: return null
            ShizukuBinderWrapper(rawBinder)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get wrapped binder for $serviceName", e)
            null
        }
    }

    /**
     * Executes a privileged shell command via Shizuku if needed as fallback.
     */
    suspend fun execCommand(vararg args: String): ShellResult = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) {
            return@withContext ShellResult(-1, "", "Shizuku not available or not authorized")
        }
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, args, null, null) as Process
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            ShellResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Throwable) {
            Log.w(TAG, "Shell execution fallback failed: ${args.joinToString(" ")}", e)
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
