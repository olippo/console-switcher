package com.example.consoleswitcher3.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.consoleswitcher3.shizuku.ScreenRecordHelper

class ScreenRecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_SCREEN_RECORD) {
            ScreenRecordHelper.stopRecording(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_STOP_SCREEN_RECORD = "com.example.consoleswitcher3.STOP_SCREEN_RECORD"
    }
}
