package com.example.consoleswitcher3.shizuku

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.receiver.ScreenRecordReceiver
import com.example.consoleswitcher3.service.ScreenRecordService

object ScreenRecordHelper {
    private const val CHANNEL_ID = "screen_record_channel"
    const val NOTIFICATION_ID = 4102

    @Volatile
    var isRecording: Boolean = false

    fun stopRecording(context: Context) {
        isRecording = false
        val appContext = context.applicationContext
        val stopIntent = Intent(appContext, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        appContext.startService(stopIntent)
    }

    suspend fun preparePermissionsViaShizuku(context: Context) {
        if (!ShizukuManager.hasShizukuPermission()) return
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ShizukuManager.execCommand(
                    "pm",
                    "grant",
                    appContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
        ShizukuManager.execCommand(
            "appops",
            "set",
            appContext.packageName,
            "PROJECT_MEDIA",
            "allow"
        )
    }

    fun buildNotification(context: Context): Notification {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification while screen recording"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(appContext, ScreenRecordReceiver::class.java).apply {
            action = ScreenRecordReceiver.ACTION_STOP_SCREEN_RECORD
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            appContext,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_record_rounded)
            .setContentTitle("Screen Recording")
            .setContentText("Recording in progress • Tap to stop")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setColor(0xFFE53935.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(stopPendingIntent)
            .addAction(R.drawable.ic_screen_record_rounded, "Stop", stopPendingIntent)
            .build()
    }
}
