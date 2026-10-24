package com.example.consoleswitcher3.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * Represents an open or recently used task/app on the Android handheld.
 */
data class DeckTask(
    val taskId: Int,
    val packageName: String,
    val componentName: String,
    val label: String,
    val icon: Drawable?,
    val previewBitmap: Bitmap? = null,
    val isCurrentTask: Boolean = false,
    val isRunning: Boolean = true,
    val lastActiveTime: Long = System.currentTimeMillis()
)
