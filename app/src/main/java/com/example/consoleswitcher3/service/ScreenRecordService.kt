package com.example.consoleswitcher3.service

import android.app.Activity
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.example.consoleswitcher3.shizuku.ScreenRecordHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var isStopping: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                stopAndSave(showToast = true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopAndSave(showToast = true)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        if (ScreenRecordHelper.isRecording) return

        val notification = ScreenRecordHelper.buildNotification(this)
        startForeground(
            ScreenRecordHelper.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Toast.makeText(applicationContext, "Failed to start screen recording", Toast.LENGTH_SHORT).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        try {
            val (width, height, densityDpi) = getRecordingMetrics()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val filename = "ScreenRecord_${sdf.format(Date())}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ScreenRecordings")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry")
            outputUri = uri

            val pfd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Failed to open output file descriptor")
            outputPfd = pfd

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(8_000_000)
                setVideoFrameRate(60)
                setVideoSize(width, height)
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }
            mediaRecorder = recorder

            virtualDisplay = projection.createVirtualDisplay(
                "ConsoleSwitcherRecord",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            ScreenRecordHelper.isRecording = true
            Toast.makeText(applicationContext, "Screen recording started", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(applicationContext, "Failed to start screen recording", Toast.LENGTH_SHORT).show()
            cleanupResources(discardUri = true)
            ScreenRecordHelper.isRecording = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun getRecordingMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.displayMetrics.densityDpi
        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            rawW = dm.widthPixels
            rawH = dm.heightPixels
        }

        // Cap max dimension at 1920 and ensure even dimensions for H.264 encoder compatibility
        val maxDim = maxOf(rawW, rawH)
        val scale = if (maxDim > 1920) 1920f / maxDim else 1f
        val w = ((rawW * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((rawH * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return Triple(w, h, densityDpi)
    }

    private fun stopAndSave(showToast: Boolean) {
        if (isStopping) return
        isStopping = true

        val hadRecording = ScreenRecordHelper.isRecording || mediaRecorder != null
        ScreenRecordHelper.isRecording = false

        cleanupResources(discardUri = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (showToast && hadRecording) {
            Toast.makeText(applicationContext, "Screen recording saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupResources(discardUri: Boolean) {
        try {
            mediaRecorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Throwable) {
        }
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        try {
            outputPfd?.close()
        } catch (_: Throwable) {
        }
        outputPfd = null

        outputUri?.let { uri ->
            try {
                if (discardUri) {
                    contentResolver.delete(uri, null, null)
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            } catch (_: Throwable) {
            }
        }
        outputUri = null
    }

    override fun onDestroy() {
        if (!isStopping) {
            ScreenRecordHelper.isRecording = false
            cleanupResources(discardUri = false)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.consoleswitcher3.ACTION_START_SCREEN_RECORD"
        const val ACTION_STOP = "com.example.consoleswitcher3.ACTION_STOP_SCREEN_RECORD"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
