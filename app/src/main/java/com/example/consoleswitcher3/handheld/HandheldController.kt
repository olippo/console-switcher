package com.example.consoleswitcher3.handheld

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.consoleswitcher3.shizuku.ShizukuManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardware control abstraction for handheld gaming devices, currently supporting
 * Moorechip-based handhelds (Retroid Pocket & AYN Odin series) via their shared
 * MoorechipSettingsLib Settings.System keys.
 */
object HandheldController {
    private const val TAG = "HandheldController"
    private const val PENDING_TIMEOUT_MS = 4000L
    private const val RP_SETTINGS_PACKAGE = "com.rp.settings"

    const val ID_PERF_MODE = "perf_mode"
    const val ID_FAN_MODE = "fan_mode"
    const val ID_CONTROLLER_STYLE = "controller_style"
    const val ID_TRIGGER_MODE = "trigger_mode"
    const val ID_JOYSTICK_LED = "joystick_led"
    const val ID_BYPASS_CHARGING = "bypass_charging"

    val HANDHELD_CONTROL_IDS: List<String> = listOf(
        ID_PERF_MODE,
        ID_FAN_MODE,
        ID_CONTROLLER_STYLE,
        ID_TRIGGER_MODE,
        ID_JOYSTICK_LED,
        ID_BYPASS_CHARGING
    )

    const val KEY_PERFORMANCE_MODE = "performance_mode"
    const val KEY_FAN_MODE = "fan_mode"
    const val KEY_TEMP_ABXY_LAYOUT = "temp_abxy_layout_mode"
    const val KEY_FLIP_BUTTON_LAYOUT = "flip_button_layout"
    const val KEY_NO_CREATE_GAMEPAD = "no_create_gamepad_button_layout"
    const val KEY_TRIGGER_INPUT_MODE = "trigger_input_mode"
    const val KEY_JOYSTICK_LIGHT_ENABLED = "joystick_light_enabled"
    const val KEY_JOYSTICK_HANDLE_LIGHT_ENABLED = "joystick_handle_light_enabled"
    const val KEY_LED_BRIGHTNESS_PERCENT = "led_light_brightness_percent"
    const val KEY_CHARGING_SEPARATION = "is_charging_separation"

    const val ACTION_UPDATE_FAN_UI = "settings.notify.update.fan.ui.action"

    data class ModeOption(
        val value: Int,
        val label: String,
        val cardTitle: String,
        val isActiveState: Boolean
    )

    val PERF_OPTIONS: List<ModeOption> = listOf(
        ModeOption(0, "Standard", "Perf: Standard", false),
        ModeOption(1, "Performance", "Perf: Performance", true),
        ModeOption(2, "High Performance", "Perf: High", true)
    )

    val FAN_OPTIONS: List<ModeOption> = listOf(
        ModeOption(0, "Off", "Fan: Off", false),
        ModeOption(1, "Quiet", "Fan: Quiet", true),
        ModeOption(4, "Smart", "Fan: Smart", true),
        ModeOption(5, "Sport", "Fan: Sport", true)
    )

    val CONTROLLER_STYLE_OPTIONS: List<ModeOption> = listOf(
        ModeOption(0, "Xbox", "Style: Xbox", true),
        ModeOption(1, "Nintendo", "Style: Nintendo", true),
        ModeOption(2, "Disconnect", "Style: Disconnected", false)
    )

    val TRIGGER_OPTIONS: List<ModeOption> = listOf(
        ModeOption(0, "Analog", "Triggers: Analog", true),
        ModeOption(1, "Digital", "Triggers: Digital", true),
        ModeOption(2, "Both", "Triggers: Both", true)
    )

    @Volatile
    private var cachedIsMoorechip: Boolean? = null

    private val pendingModeValues = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun isMoorechipDevice(context: Context): Boolean {
        cachedIsMoorechip?.let { return it }
        val byBuild = isMoorechipBuild(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        val byPackage = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(RP_SETTINGS_PACKAGE, 0)
            true
        } catch (ignored: Throwable) {
            false
        }
        val detected = byBuild || byPackage
        cachedIsMoorechip = detected
        return detected
    }

    fun isMoorechipBuild(manufacturer: String?, brand: String?, model: String?): Boolean {
        if (manufacturer.equals("Moorechip", ignoreCase = true)) return true
        if (brand.equals("Retroid", ignoreCase = true) || brand.equals("AYN", ignoreCase = true)) {
            return true
        }
        val m = model.orEmpty()
        return m.contains("Retroid", ignoreCase = true) ||
            m.contains("Odin", ignoreCase = true) ||
            m.contains("Thor", ignoreCase = true)
    }

    fun isHandheldControl(id: String): Boolean = id in HANDHELD_CONTROL_IDS

    fun isMultiModeControl(id: String): Boolean {
        return id == ID_PERF_MODE ||
            id == ID_FAN_MODE ||
            id == ID_CONTROLLER_STYLE ||
            id == ID_TRIGGER_MODE
    }

    fun getModeOptions(id: String): List<ModeOption> {
        return when (id) {
            ID_PERF_MODE -> PERF_OPTIONS
            ID_FAN_MODE -> FAN_OPTIONS
            ID_CONTROLLER_STYLE -> CONTROLLER_STYLE_OPTIONS
            ID_TRIGGER_MODE -> TRIGGER_OPTIONS
            else -> emptyList()
        }
    }

    fun nextCycleValue(id: String, currentValue: Int): Int {
        return when (id) {
            ID_PERF_MODE -> when (currentValue) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            ID_FAN_MODE -> when (currentValue) {
                0 -> 1
                1 -> 4
                4 -> 5
                else -> 0
            }
            // Only cycle between Xbox (0) and Retro (1) on X press so the user never
            // accidentally disconnects the built-in gamepad. Disconnect (2) remains
            // selectable inside the A modal picker.
            ID_CONTROLLER_STYLE -> if (currentValue == 0) 1 else 0
            ID_TRIGGER_MODE -> when (currentValue) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            else -> if (currentValue == 0) 1 else 0
        }
    }

    fun parseJoystickLedEnabled(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.split(",").any { it.trim() == "1" }
    }

    fun resolveCardState(id: String, modeOrBoolValue: Int): Pair<String, Boolean> {
        return when (id) {
            ID_PERF_MODE -> {
                val match = PERF_OPTIONS.firstOrNull { it.value == modeOrBoolValue }
                if (match != null) match.cardTitle to match.isActiveState
                else "Perf: Standard" to false
            }
            ID_FAN_MODE -> {
                val match = FAN_OPTIONS.firstOrNull { it.value == modeOrBoolValue }
                when {
                    match != null -> match.cardTitle to match.isActiveState
                    modeOrBoolValue == 2 -> "Fan: Balance" to true
                    modeOrBoolValue == 3 -> "Fan: Performance" to true
                    modeOrBoolValue == 6 -> "Fan: Custom" to true
                    else -> "Fan: Off" to false
                }
            }
            ID_CONTROLLER_STYLE -> {
                val match = CONTROLLER_STYLE_OPTIONS.firstOrNull { it.value == modeOrBoolValue }
                if (match != null) match.cardTitle to match.isActiveState
                else "Style: Nintendo" to true
            }
            ID_TRIGGER_MODE -> {
                val match = TRIGGER_OPTIONS.firstOrNull { it.value == modeOrBoolValue }
                if (match != null) match.cardTitle to match.isActiveState
                else "Triggers: Both" to true
            }
            ID_JOYSTICK_LED -> "Joystick LED" to (modeOrBoolValue != 0)
            ID_BYPASS_CHARGING -> "Charging Separation" to (modeOrBoolValue != 0)
            else -> id to (modeOrBoolValue != 0)
        }
    }

    fun setPendingValue(id: String, targetValue: Int) {
        pendingModeValues[id] = targetValue to System.currentTimeMillis()
    }

    fun readCurrentValue(context: Context, id: String): Int {
        val pending = pendingModeValues[id]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.second
            if (elapsed < PENDING_TIMEOUT_MS) {
                val raw = readRawValue(context, id)
                if (raw == pending.first) {
                    pendingModeValues.remove(id)
                    return raw
                }
                return pending.first
            } else {
                pendingModeValues.remove(id)
            }
        }
        return readRawValue(context, id)
    }

    fun readRawValue(context: Context, id: String): Int {
        val cr = context.contentResolver
        return try {
            when (id) {
                ID_PERF_MODE -> Settings.System.getInt(cr, KEY_PERFORMANCE_MODE, 0).coerceIn(0, 2)
                ID_FAN_MODE -> Settings.System.getInt(cr, KEY_FAN_MODE, 0)
                ID_CONTROLLER_STYLE -> Settings.System.getInt(cr, KEY_TEMP_ABXY_LAYOUT, 1).coerceIn(0, 2)
                ID_TRIGGER_MODE -> Settings.System.getInt(cr, KEY_TRIGGER_INPUT_MODE, 2).coerceIn(0, 2)
                ID_JOYSTICK_LED -> {
                    val raw = Settings.System.getString(cr, KEY_JOYSTICK_LIGHT_ENABLED)
                    if (parseJoystickLedEnabled(raw)) 1 else 0
                }
                ID_BYPASS_CHARGING -> {
                    if (Settings.System.getInt(cr, KEY_CHARGING_SEPARATION, 0) != 0) 1 else 0
                }
                else -> 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read raw setting for $id: ${e.message}")
            0
        }
    }

    suspend fun applyValue(context: Context, id: String, targetValue: Int): Boolean {
        return when (id) {
            ID_PERF_MODE -> {
                writeSystemInt(context, KEY_PERFORMANCE_MODE, targetValue.coerceIn(0, 2))
            }
            ID_FAN_MODE -> {
                val (ok, usedShizuku) = writeSystemIntWithSource(context, KEY_FAN_MODE, targetValue)
                try {
                    context.sendBroadcast(Intent(ACTION_UPDATE_FAN_UI))
                } catch (ignored: Throwable) {}
                if (usedShizuku && ShizukuManager.hasShizukuPermission()) {
                    try {
                        ShizukuManager.execCommand("am", "broadcast", "-a", ACTION_UPDATE_FAN_UI)
                    } catch (ignored: Throwable) {}
                }
                ok
            }
            ID_CONTROLLER_STYLE -> {
                when (targetValue.coerceIn(0, 2)) {
                    0 -> {
                        val a = writeSystemInt(context, KEY_TEMP_ABXY_LAYOUT, 0)
                        val b = writeSystemInt(context, KEY_FLIP_BUTTON_LAYOUT, 1)
                        val c = writeSystemInt(context, KEY_NO_CREATE_GAMEPAD, 0)
                        a && b && c
                    }
                    1 -> {
                        val a = writeSystemInt(context, KEY_TEMP_ABXY_LAYOUT, 1)
                        val b = writeSystemInt(context, KEY_FLIP_BUTTON_LAYOUT, 0)
                        val c = writeSystemInt(context, KEY_NO_CREATE_GAMEPAD, 0)
                        a && b && c
                    }
                    else -> {
                        val a = writeSystemInt(context, KEY_TEMP_ABXY_LAYOUT, 2)
                        val b = writeSystemInt(context, KEY_NO_CREATE_GAMEPAD, 1)
                        val c = writeSystemInt(context, KEY_FLIP_BUTTON_LAYOUT, 0)
                        a && b && c
                    }
                }
            }
            ID_TRIGGER_MODE -> {
                writeSystemInt(context, KEY_TRIGGER_INPUT_MODE, targetValue.coerceIn(0, 2))
            }
            ID_JOYSTICK_LED -> {
                val enable = targetValue != 0
                val ok = writeSystemString(
                    context,
                    KEY_JOYSTICK_LIGHT_ENABLED,
                    if (enable) "1,1" else "0,0"
                )
                try {
                    if (Settings.System.getString(context.contentResolver, KEY_JOYSTICK_HANDLE_LIGHT_ENABLED) != null) {
                        writeSystemString(
                            context,
                            KEY_JOYSTICK_HANDLE_LIGHT_ENABLED,
                            if (enable) "1,1,1,1" else "0,0,0,0"
                        )
                    }
                } catch (ignored: Throwable) {}
                if (enable) {
                    try {
                        val brightness = Settings.System.getFloat(
                            context.contentResolver,
                            KEY_LED_BRIGHTNESS_PERCENT,
                            0.5f
                        )
                        if (brightness <= 0.01f) {
                            writeSystemString(context, KEY_LED_BRIGHTNESS_PERCENT, "0.5")
                        }
                    } catch (ignored: Throwable) {}
                }
                ok
            }
            ID_BYPASS_CHARGING -> {
                writeSystemInt(context, KEY_CHARGING_SEPARATION, if (targetValue != 0) 1 else 0)
            }
            else -> false
        }
    }

    private suspend fun writeSystemInt(context: Context, key: String, value: Int): Boolean {
        return writeSystemIntWithSource(context, key, value).first
    }

    private suspend fun writeSystemIntWithSource(
        context: Context,
        key: String,
        value: Int
    ): Pair<Boolean, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.contentResolver, key, value)
                val readBack = Settings.System.getInt(context.contentResolver, key, Int.MIN_VALUE)
                if (readBack == value) {
                    return true to false
                }
            } catch (ignored: Throwable) {}
        }
        if (ShizukuManager.hasShizukuPermission()) {
            val res = ShizukuManager.execCommand("settings", "put", "system", key, value.toString())
            if (res.isSuccess) {
                return true to true
            }
        }
        return false to false
    }

    private suspend fun writeSystemString(context: Context, key: String, value: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
            try {
                Settings.System.putString(context.contentResolver, key, value)
                val readBack = Settings.System.getString(context.contentResolver, key)
                if (readBack == value) {
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        if (ShizukuManager.hasShizukuPermission()) {
            val res = ShizukuManager.execCommand("settings", "put", "system", key, value)
            if (res.isSuccess) {
                return true
            }
        }
        return false
    }
}
