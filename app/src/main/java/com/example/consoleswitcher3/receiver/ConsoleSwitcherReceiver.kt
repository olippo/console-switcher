package com.example.consoleswitcher3.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.consoleswitcher3.OverlayActivity

class ConsoleSwitcherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SWITCHER) {
            val openIntent = Intent(context, OverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(openIntent)
        }
    }

    companion object {
        const val ACTION_OPEN_SWITCHER = "com.example.consoleswitcher3.OPEN_SWITCHER"
    }
}
