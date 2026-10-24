package com.example.consoleswitcher3.task

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.model.DeckTask
import com.example.consoleswitcher3.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

object TaskManager {
    private const val TAG = "TaskManager"

    /**
     * Queries currently open and recent tasks on the device.
     */
    suspend fun getRecentTasks(context: Context): List<DeckTask> = withContext(Dispatchers.IO) {
        val ownPackage = context.packageName
        val homePackages = getHomePackages(context)

        // 1. Try privileged Binder call via IActivityTaskManager
        if (ShizukuManager.hasShizukuPermission()) {
            try {
                val binderTasks = getTasksViaBinder(context, ownPackage, homePackages)
                if (binderTasks != null) {
                    Log.d(TAG, "Successfully loaded ${binderTasks.size} tasks via IActivityTaskManager binder")
                    return@withContext binderTasks
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Binder query failed, falling back to shell parser", e)
            }

            // 2. Try privileged dumpsys activity recents shell parsing
            try {
                val shellTasks = getTasksViaDumpsys(context, ownPackage, homePackages)
                if (shellTasks != null) {
                    Log.d(TAG, "Successfully loaded ${shellTasks.size} tasks via dumpsys recents")
                    return@withContext shellTasks
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Dumpsys query failed, returning empty tasks", e)
            }

            // Both privileged paths failed — return empty rather than faking it.
            return@withContext emptyList()
        }

        // Shizuku is not available or not authorized — return empty.
        // The overlay displays a "set up Shizuku" prompt in this state.
        emptyList()
    }

    fun getHomePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val homePackages = mutableSetOf<String>()

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                0
            }
            val resolveInfos = pm.queryIntentActivities(homeIntent, flags)
            for (info in resolveInfos) {
                info.activityInfo?.packageName?.let { homePackages.add(it) }
            }

            val defaultHome = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            defaultHome?.activityInfo?.packageName?.let { homePackages.add(it) }

            val secHomeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
            val secResolveInfos = pm.queryIntentActivities(secHomeIntent, flags)
            for (info in secResolveInfos) {
                info.activityInfo?.packageName?.let { homePackages.add(it) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying home packages", e)
        }

        return homePackages
    }

    fun isHomeOrLauncher(
        targetPackage: String?,
        label: String?,
        homePackages: Set<String>
    ): Boolean {
        if (targetPackage.isNullOrEmpty()) return false
        if (homePackages.contains(targetPackage)) return true
        if (targetPackage.startsWith("com.android.launcher") ||
            targetPackage.contains(".quickstep") ||
            targetPackage == "com.android.launcher3" ||
            targetPackage == "com.google.android.apps.nexuslauncher"
        ) {
            return true
        }
        if (label != null && label.equals("Quickstep", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * Calls IActivityTaskManager.getRecentTasks(30, 2, 0) via Shizuku binder.
     */
    private fun getTasksViaBinder(
        context: Context,
        ownPackage: String,
        homePackages: Set<String>
    ): List<DeckTask>? {
        val binder = ShizukuManager.getSystemServiceBinder("activity_task") ?: return null
        val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val atm = asInterface.invoke(null, binder) ?: return null

        val getRecentTasksMethod = atm.javaClass.getMethod(
            "getRecentTasks",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        // 2 = ActivityManager.RECENT_IGNORE_UNAVAILABLE
        val parceledSlice = getRecentTasksMethod.invoke(atm, 30, 2, 0) ?: return null
        val getListMethod = parceledSlice.javaClass.getMethod("getList")
        val rawList = getListMethod.invoke(parceledSlice) as? List<*> ?: return null

        val pm = context.packageManager
        val result = mutableListOf<DeckTask>()
        val seenPackages = mutableSetOf<String>()

        var currentRunningTaskId: Int? = null

        // Strategy 1: Check live running tasks in z-order behind OverlayActivity
        val runningTasks = getRunningTasksFromAtm(atm) ?: getRunningTasksFromAm() ?: getRootTasksFromAtm(atm)
        if (runningTasks != null) {
            for (rt in runningTasks) {
                if (rt == null) continue
                val details = parseRunningTask(rt, homePackages) ?: continue
                val pkg = details.packageName
                if (pkg.isNullOrEmpty() || pkg == ownPackage) continue

                // First non-own task directly beneath OverlayActivity:
                if (details.isHome) {
                    currentRunningTaskId = -1 // User opened from Home / Launcher, no app is currently open
                } else {
                    currentRunningTaskId = details.taskId
                }
                break
            }
        }

        // Strategy 2: If running tasks couldn't be queried, only check if any recent task is explicitly visible on screen.
        // When user is at Home, background recents have isVisible == false.
        if (currentRunningTaskId == null) {
            for (item in rawList) {
                if (item == null) continue
                val itemClass = item.javaClass
                val isVisible = try {
                    itemClass.getField("isVisible").getBoolean(item)
                } catch (e: Throwable) {
                    try {
                        val m = itemClass.getMethod("isVisible")
                        m.invoke(item) as? Boolean
                    } catch (e2: Throwable) {
                        null
                    }
                }

                if (isVisible == true) {
                    val details = parseRunningTask(item, homePackages)
                    if (details != null && !details.packageName.isNullOrEmpty() && details.packageName != ownPackage) {
                        if (details.isHome) {
                            currentRunningTaskId = -1
                        } else {
                            currentRunningTaskId = details.taskId
                        }
                        break
                    }
                }
            }
        }

        for (item in rawList) {
            if (item == null) continue
            try {
                val itemClass = item.javaClass

                // Exclude home or recents task types
                val activityType = try {
                    val m = itemClass.getMethod("getActivityType")
                    m.invoke(item) as? Int
                } catch (e: Throwable) {
                    try {
                        val f = itemClass.getField("topActivityType")
                        f.getInt(item)
                    } catch (e2: Throwable) { null }
                }
                // 2 = ACTIVITY_TYPE_HOME, 3 = ACTIVITY_TYPE_RECENTS
                if (activityType == 2 || activityType == 3) {
                    continue
                }

                val baseIntentField = try { itemClass.getField("baseIntent") } catch (e: Throwable) { null }
                val baseIntent = baseIntentField?.get(item) as? Intent
                if (baseIntent?.hasCategory(Intent.CATEGORY_HOME) == true ||
                    baseIntent?.categories?.contains(Intent.CATEGORY_HOME) == true
                ) {
                    continue
                }

                // Retrieve taskId or id field
                val taskId = try {
                    val field = itemClass.getField("taskId")
                    field.getInt(item)
                } catch (e: Throwable) {
                    val field = itemClass.getField("id")
                    field.getInt(item)
                }

                // Retrieve component / baseIntent / realActivity
                var targetPackage: String? = null
                var componentStr = ""

                try {
                    val realActivityField = itemClass.getField("realActivity")
                    val realActivity = realActivityField.get(item) as? ComponentName
                    if (realActivity != null) {
                        targetPackage = realActivity.packageName
                        componentStr = realActivity.flattenToShortString()
                    }
                } catch (ignored: Throwable) {}

                if (targetPackage == null) {
                    try {
                        val topActivityField = itemClass.getField("topActivity")
                        val topActivity = topActivityField.get(item) as? ComponentName
                        if (topActivity != null) {
                            targetPackage = topActivity.packageName
                            componentStr = topActivity.flattenToShortString()
                        }
                    } catch (ignored: Throwable) {}
                }

                if (targetPackage == null && baseIntent != null && baseIntent.component != null) {
                    targetPackage = baseIntent.component?.packageName
                    componentStr = baseIntent.component?.flattenToShortString() ?: ""
                }

                if (targetPackage.isNullOrEmpty() || targetPackage == ownPackage) continue
                if (isHomeOrLauncher(targetPackage, null, homePackages)) continue
                if (seenPackages.contains(targetPackage)) continue

                val (label, icon) = getAppDetails(context, pm, targetPackage)
                if (isHomeOrLauncher(targetPackage, label, homePackages)) continue

                seenPackages.add(targetPackage)

                val isCurrent = (currentRunningTaskId != null && currentRunningTaskId > 0 && taskId == currentRunningTaskId)
                val previewBitmap = if (isCurrent) null else getTaskSnapshotBitmap(atm, taskId)

                result.add(
                    DeckTask(
                        taskId = taskId,
                        packageName = targetPackage,
                        componentName = componentStr,
                        label = label,
                        icon = icon,
                        previewBitmap = previewBitmap,
                        isCurrentTask = isCurrent,
                        isRunning = true
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Error parsing TaskInfo item", e)
            }
        }
        return result
    }

    /**
     * Retrieves the live cached TaskSnapshot bitmap for the given taskId via IActivityTaskManager.
     * Tries low-res snapshot thumbnail first:
     * Android system server already caches this pre-scaled thumbnail (~540x960 / 360x640),
     * which matches our 320dp x 180dp card size perfectly, avoids multi-megabyte GPU texture uploads,
     * and eliminates rendering stalls on complex Vulkan/game buffers (e.g. Eden).
     * Falls back to full-res snapshot and downsamples it if low-res is not available yet.
     */
    fun getTaskSnapshotBitmap(atm: Any, taskId: Int): Bitmap? {
        if (taskId <= 0) return null
        getSnapshotInternal(atm, taskId, lowRes = true)?.let { return it }
        return getSnapshotInternal(atm, taskId, lowRes = false)
    }

    private fun getSnapshotInternal(atm: Any, taskId: Int, lowRes: Boolean): Bitmap? {
        return try {
            val snapshotObj = try {
                val m = atm.javaClass.getMethod(
                    "getTaskSnapshot",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(atm, taskId, lowRes)
            } catch (e: NoSuchMethodException) {
                try {
                    val m = atm.javaClass.getMethod(
                        "getTaskSnapshot",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                    m.invoke(atm, taskId, lowRes, false)
                } catch (e2: Throwable) {
                    null
                }
            } ?: return null

            val snapshotClass = snapshotObj.javaClass
            val getHwBufferMethod = try {
                snapshotClass.getMethod("getHardwareBuffer")
            } catch (e: NoSuchMethodException) {
                snapshotClass.getMethod("getSnapshot")
            }

            val hwBuffer = getHwBufferMethod.invoke(snapshotObj) as? HardwareBuffer ?: return null
            val colorSpace = try {
                val getColorSpaceMethod = snapshotClass.getMethod("getColorSpace")
                getColorSpaceMethod.invoke(snapshotObj) as? ColorSpace
            } catch (ignored: Throwable) {
                null
            }

            val hardwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (colorSpace != null) {
                    try {
                        Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                    } catch (e: Throwable) {
                        Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
                    }
                } else {
                    Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } else {
                null
            }
            hwBuffer.close()

            if (hardwareBitmap == null) return null
            val sanitized = sanitizeAndDownscaleSnapshot(hardwareBitmap)
            if (hardwareBitmap !== sanitized && !hardwareBitmap.isRecycled) {
                try { hardwareBitmap.recycle() } catch (ignored: Throwable) {}
            }
            sanitized
        } catch (e: Throwable) {
            Log.d(TAG, "Task snapshot unavailable for task $taskId: ${e.message}")
            null
        }
    }

    /**
     * Sanitizes and downscales snapshots to a standard sRGB ARGB_8888 software bitmap.
     * Prevents HWUI GPU pipeline stalls, color conversion shader overhead, and texture
     * cache thrashing from wide-gamut / Vulkan hardware buffers (like Eden emulator).
     */
    private fun sanitizeAndDownscaleSnapshot(
        source: Bitmap,
        maxWidth: Int = 640,
        maxHeight: Int = 360
    ): Bitmap? {
        return try {
            val isHw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE

            val softwareBitmap = if (isHw) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            } ?: return null

            val srcWidth = softwareBitmap.width
            val srcHeight = softwareBitmap.height
            if (srcWidth <= 0 || srcHeight <= 0) return null

            val scale = minOf(
                maxWidth.toFloat() / srcWidth,
                maxHeight.toFloat() / srcHeight,
                1.0f
            )
            val targetW = (srcWidth * scale).toInt().coerceAtLeast(1)
            val targetH = (srcHeight * scale).toInt().coerceAtLeast(1)

            val srgbBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.createBitmap(
                    targetW,
                    targetH,
                    Bitmap.Config.ARGB_8888,
                    true,
                    ColorSpace.get(ColorSpace.Named.SRGB)
                )
            } else {
                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            }

            val canvas = Canvas(srgbBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val srcRect = Rect(0, 0, srcWidth, srcHeight)
            val dstRect = Rect(0, 0, targetW, targetH)
            canvas.drawBitmap(softwareBitmap, srcRect, dstRect, paint)

            if (softwareBitmap !== source && softwareBitmap !== srgbBitmap && !softwareBitmap.isRecycled) {
                softwareBitmap.recycle()
            }

            srgbBitmap
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to sanitize and downscale snapshot", e)
            null
        }
    }

    /**
     * Re-tries snapshots for tasks that came back without a preview.
     * The task that was foreground when the overlay opened often has no
     * cached snapshot yet — it appears a few hundred ms after backgrounding.
     * Returns a new list with previews filled in where now available.
     */
    suspend fun refreshMissingPreviews(tasks: List<DeckTask>): List<DeckTask> = withContext(Dispatchers.IO) {
        if (tasks.none { it.previewBitmap == null && it.taskId > 0 && !it.isCurrentTask }) return@withContext tasks
        try {
            kotlinx.coroutines.delay(600)
        } catch (ignored: Throwable) {}
        try {
            val binder = ShizukuManager.getSystemServiceBinder("activity_task") ?: return@withContext tasks
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val atm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: return@withContext tasks
            tasks.map { task ->
                if (task.previewBitmap == null && task.taskId > 0 && !task.isCurrentTask) {
                    val bmp = getTaskSnapshotBitmap(atm, task.taskId)
                    if (bmp != null) task.copy(previewBitmap = bmp) else task
                } else task
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Snapshot refresh failed", e)
            tasks
        }
    }

    /**
     * Parses dumpsys activity recents via Shizuku shell.
     */
    private suspend fun getTasksViaDumpsys(
        context: Context,
        ownPackage: String,
        homePackages: Set<String>
    ): List<DeckTask>? {
        val result = ShizukuManager.execCommand("dumpsys", "activity", "recents")
        if (!result.isSuccess) return null
        if (result.output.isEmpty()) return emptyList()

        val pm = context.packageManager
        val list = mutableListOf<DeckTask>()
        val seenPackages = mutableSetOf<String>()

        // Line pattern: * Recent #0: Task{41ea720 #32 type=standard I=com.example.app/...}
        val pattern = Pattern.compile("\\*\\s+Recent\\s+#\\d+:\\s+Task\\{[0-9a-fA-F]+\\s+#(\\d+)\\s+.*?I=([^/\\s\\}]+)(?:/([^\\s\\}]+))?")

        var currentRunningTaskId: Int? = null

        // Query top-most task hierarchy from dumpsys activity top
        try {
            val topResult = ShizukuManager.execCommand("dumpsys", "activity", "top")
            if (topResult.isSuccess && topResult.output.isNotEmpty()) {
                currentRunningTaskId = parseTopTaskFromDumpsysTop(topResult.output, ownPackage, homePackages)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "dumpsys activity top check failed", e)
        }

        // Fallback: check if dumpsys activity recents explicitly records Recent #0 as type=home
        if (currentRunningTaskId == null) {
            val peekMatcher = pattern.matcher(result.output)
            while (peekMatcher.find()) {
                val fullMatch = peekMatcher.group(0) ?: ""
                val pkg = peekMatcher.group(2) ?: ""
                val taskId = peekMatcher.group(1)?.toIntOrNull() ?: continue
                if (pkg == ownPackage) continue

                if (fullMatch.contains("type=home") || isHomeOrLauncher(pkg, null, homePackages)) {
                    currentRunningTaskId = -1 // Home was on top
                }
                break
            }
        }

        val matcher = pattern.matcher(result.output)

        // Best-effort snapshot source for dumpsys path (previously had no previews at all)
        val atm: Any? = try {
            val binder = ShizukuManager.getSystemServiceBinder("activity_task") ?: null
            if (binder != null) {
                val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
                stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            } else null
        } catch (ignored: Throwable) { null }

        while (matcher.find()) {
            try {
                val fullMatch = matcher.group(0) ?: ""
                if (fullMatch.contains("type=home") || fullMatch.contains("type=recents")) {
                    continue
                }

                val taskId = matcher.group(1)?.toIntOrNull() ?: continue
                val pkg = matcher.group(2) ?: continue
                val comp = matcher.group(3) ?: ""

                if (pkg == ownPackage || seenPackages.contains(pkg)) continue
                if (isHomeOrLauncher(pkg, null, homePackages)) continue

                val (label, icon) = getAppDetails(context, pm, pkg)
                if (isHomeOrLauncher(pkg, label, homePackages)) continue

                seenPackages.add(pkg)

                val isCurrent = (currentRunningTaskId != null && currentRunningTaskId > 0 && taskId == currentRunningTaskId)
                val preview = if (isCurrent) null else (if (atm != null) getTaskSnapshotBitmap(atm, taskId) else null)

                list.add(
                    DeckTask(
                        taskId = taskId,
                        packageName = pkg,
                        componentName = if (comp.isNotEmpty()) "$pkg/$comp" else pkg,
                        label = label,
                        icon = icon,
                        previewBitmap = preview,
                        isCurrentTask = isCurrent,
                        isRunning = true
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Error parsing dumpsys line", e)
            }
        }
        return list
    }

    private fun getAppDetails(
        context: Context,
        pm: PackageManager,
        packageName: String
    ): Pair<String, Drawable?> {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val rawIcon = pm.getApplicationIcon(appInfo)
            val icon = rasterizeIcon(context, rawIcon)
            Pair(label, icon)
        } catch (e: Throwable) {
            Pair(packageName, null)
        }
    }

    /**
     * Pre-rasterizes any Drawable (AdaptiveIconDrawable, VectorDrawable, etc.) into
     * a clean, static BitmapDrawable once on Dispatchers.IO.
     * Prevents UI-thread path evaluation, adaptive mask recalculation, and bounds thrashing.
     */
    fun rasterizeIcon(context: Context, rawIcon: Drawable?, sizeDp: Int = 96): Drawable? {
        if (rawIcon == null) return null
        return try {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt().coerceIn(96, 256)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = Rect(rawIcon.bounds)
            rawIcon.setBounds(0, 0, sizePx, sizePx)
            rawIcon.draw(canvas)
            rawIcon.bounds = oldBounds
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to rasterize app icon", e)
            rawIcon
        }
    }

    /**
     * Switches to the specified task with a consistent zoom-from-center animation.
     */
    suspend fun switchTask(context: Context, task: DeckTask): Boolean = withContext(Dispatchers.IO) {
        // Shared zoom-from-center launch options so every reopen looks the same
        // regardless of which switch path succeeds.
        val zoomOptions: Bundle? = try {
            android.app.ActivityOptions.makeCustomAnimation(
                context,
                R.anim.zoom_enter,
                R.anim.zoom_exit
            ).toBundle()
        } catch (ignored: Throwable) {
            null
        }

        // Method 1: IActivityTaskManager.startActivityFromRecents
        if (ShizukuManager.hasShizukuPermission() && task.taskId > 0) {
            try {
                val binder = ShizukuManager.getSystemServiceBinder("activity_task")
                if (binder != null) {
                    val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
                    val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                    val atm = asInterface.invoke(null, binder)
                    if (atm != null) {
                        val startMethod = atm.javaClass.getMethod(
                            "startActivityFromRecents",
                            Int::class.javaPrimitiveType,
                            Bundle::class.java
                        )
                        val code = startMethod.invoke(atm, task.taskId, zoomOptions) as? Int ?: 0
                        Log.d(TAG, "startActivityFromRecents returned code: $code for task ${task.taskId}")
                        return@withContext true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed startActivityFromRecents via binder", e)
            }

            // Method 2: Shell am start via Shizuku
            try {
                if (task.componentName.isNotEmpty()) {
                    val res = ShizukuManager.execCommand(
                        "am", "start",
                        "--activity-reorder-to-front",
                        "-n", task.componentName
                    )
                    if (res.isSuccess) {
                        Log.d(TAG, "am start succeeded for ${task.componentName}")
                        return@withContext true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "am start via Shizuku failed", e)
            }
        }

        // Method 3: Standard Android Intent Fallback
        return@withContext withContext(Dispatchers.Main) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(task.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    try {
                        if (zoomOptions != null) {
                            context.startActivity(launchIntent, zoomOptions)
                        } else {
                            context.startActivity(launchIntent)
                        }
                    } catch (ignored: Throwable) {
                        context.startActivity(launchIntent)
                    }
                    true
                } else {
                    false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Standard Intent launch failed for ${task.packageName}", e)
                false
            }
        }
    }

    /**
     * Force stops / kills the specified task.
     */
    suspend fun killTask(context: Context, task: DeckTask): Boolean = withContext(Dispatchers.IO) {
        var success = false
        if (ShizukuManager.hasShizukuPermission()) {
            // Remove task from recents
            if (task.taskId > 0) {
                try {
                    val binder = ShizukuManager.getSystemServiceBinder("activity_task")
                    if (binder != null) {
                        val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
                        val atm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                        val removeTaskMethod = atm?.javaClass?.getMethod("removeTask", Int::class.javaPrimitiveType)
                        removeTaskMethod?.invoke(atm, task.taskId)
                        success = true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "removeTask failed", e)
                }
            }

            // Force stop the package
            try {
                val res = ShizukuManager.execCommand("am", "force-stop", task.packageName)
                if (res.isSuccess) success = true
            } catch (e: Throwable) {
                Log.w(TAG, "am force-stop failed", e)
            }
        }
        success
    }

    data class TopTaskDetails(
        val taskId: Int,
        val packageName: String?,
        val isHome: Boolean
    )

    fun getRunningTasksFromAtm(atm: Any, maxNum: Int = 10): List<*>? {
        val methods = atm.javaClass.methods.filter { it.name == "getTasks" }
        for (m in methods) {
            val params = m.parameterTypes
            try {
                val result = when (params.size) {
                    1 -> m.invoke(atm, maxNum)
                    2 -> m.invoke(atm, maxNum, false)
                    3 -> m.invoke(atm, maxNum, false, false)
                    4 -> m.invoke(atm, maxNum, false, false, 0)
                    else -> null
                }
                if (result is List<*> && result.isNotEmpty()) {
                    return result
                }
            } catch (e: Throwable) {
                Log.d(TAG, "atm.getTasks invoke failed with ${params.size} params: ${e.message}")
            }
        }
        return null
    }

    fun getRunningTasksFromAm(maxNum: Int = 10): List<*>? {
        return try {
            val binder = ShizukuManager.getSystemServiceBinder("activity") ?: return null
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val am = asInterface.invoke(null, binder) ?: return null
            val methods = am.javaClass.methods.filter { it.name == "getRunningTasks" }
            for (m in methods) {
                val params = m.parameterTypes
                try {
                    val result = when (params.size) {
                        1 -> m.invoke(am, maxNum)
                        2 -> m.invoke(am, maxNum, 0)
                        else -> null
                    }
                    if (result is List<*> && result.isNotEmpty()) {
                        return result
                    }
                } catch (ignored: Throwable) {}
            }
            null
        } catch (e: Throwable) {
            null
        }
    }

    fun getRootTasksFromAtm(atm: Any): List<*>? {
        return try {
            val m = atm.javaClass.methods.firstOrNull { it.name == "getAllRootTaskInfos" } ?: return null
            val result = m.invoke(atm) as? List<*>
            if (!result.isNullOrEmpty()) result else null
        } catch (e: Throwable) {
            null
        }
    }

    fun parseRunningTask(item: Any, homePackages: Set<String>): TopTaskDetails? {
        val itemClass = item.javaClass

        val taskId = try {
            itemClass.getField("taskId").getInt(item)
        } catch (e: Throwable) {
            try {
                itemClass.getField("id").getInt(item)
            } catch (e2: Throwable) {
                -1
            }
        }

        var targetPkg: String? = null
        try {
            val topAct = itemClass.getField("topActivity").get(item) as? ComponentName
            targetPkg = topAct?.packageName
        } catch (ignored: Throwable) {}

        if (targetPkg.isNullOrEmpty()) {
            try {
                val realAct = itemClass.getField("realActivity").get(item) as? ComponentName
                targetPkg = realAct?.packageName
            } catch (ignored: Throwable) {}
        }

        if (targetPkg.isNullOrEmpty()) {
            try {
                val baseAct = itemClass.getField("baseActivity").get(item) as? ComponentName
                targetPkg = baseAct?.packageName
            } catch (ignored: Throwable) {}
        }

        if (targetPkg.isNullOrEmpty()) {
            try {
                val baseIntent = itemClass.getField("baseIntent").get(item) as? Intent
                targetPkg = baseIntent?.component?.packageName
            } catch (ignored: Throwable) {}
        }

        val activityType = try {
            val m = itemClass.getMethod("getActivityType")
            m.invoke(item) as? Int
        } catch (e: Throwable) {
            try {
                itemClass.getField("topActivityType").getInt(item)
            } catch (e2: Throwable) {
                try {
                    itemClass.getField("activityType").getInt(item)
                } catch (e3: Throwable) {
                    null
                }
            }
        }

        val isHome = activityType == 2 || isHomeOrLauncher(targetPkg, null, homePackages)

        return TopTaskDetails(
            taskId = taskId,
            packageName = targetPkg,
            isHome = isHome
        )
    }

    fun parseTopTaskFromDumpsysTop(
        output: String,
        ownPackage: String,
        homePackages: Set<String>
    ): Int? {
        val topPattern = Pattern.compile("(?:TASK\\s+([^/\\s]+)\\s+id=(\\d+)|ACTIVITY\\s+([^/\\s]+)/[^\\s]+)")
        val topMatcher = topPattern.matcher(output)
        while (topMatcher.find()) {
            val pkg = topMatcher.group(1) ?: topMatcher.group(3) ?: continue
            val tId = topMatcher.group(2)?.toIntOrNull() ?: -1
            if (pkg.isEmpty() || pkg == ownPackage) continue

            return if (isHomeOrLauncher(pkg, null, homePackages)) {
                -1 // Home is top
            } else {
                tId
            }
        }
        return null
    }
}
