package com.example.consoleswitcher3

import android.app.Application
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ConsoleSwitcherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Bypass hidden API restrictions on Android 9+ (especially Android 13/14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.d(TAG, "HiddenApiBypass exemptions applied successfully.")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply HiddenApiBypass exemptions", e)
            }
        }
    }

    companion object {
        private const val TAG = "ConsoleSwitcherApp"
        lateinit var instance: ConsoleSwitcherApp
            private set
    }
}
