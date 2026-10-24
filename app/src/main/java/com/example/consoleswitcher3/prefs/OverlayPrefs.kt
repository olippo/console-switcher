package com.example.consoleswitcher3.prefs

import android.content.Context

object OverlayPrefs {
    private const val FILE = "consoleswitcher"
    const val KEY_ANIM_SPEED = "nav_anim_speed_v4"
    const val KEY_CLOSE_ANIM_SPEED = "close_anim_speed_v1"
    const val KEY_SLIDE_DELAY = "slide_delay_ms_v1"
    const val KEY_SIDE_SCALE = "side_scale"
    const val KEY_CARD_GAP = "card_gap"
    const val KEY_BOTTOM_GAP = "bottom_gap"
    const val KEY_BG_COLOR = "bg_color"
    const val KEY_BG_OPACITY = "bg_opacity"
    const val KEY_BG_GRADIENT_HEIGHT = "bg_gradient_height"
    const val KEY_BORDER_COLOR = "border_color"
    const val KEY_BORDER_WIDTH = "border_width"
    const val KEY_CARD_BG = "card_bg"
    const val KEY_CARD_FOCUSED_BG = "card_focused_bg"
    const val KEY_CARD_BORDER = "card_border"
    const val KEY_PREVIEW_BG = "preview_bg"

    /** 1.0 = default console navigation speed (matches former 0.5x). Higher = faster. */
    const val DEFAULT_NAV_SPEED = 1.0f
    /** Slowest navigation animation on the slider (0.5x). */
    const val MIN_NAV_SPEED = 0.5f
    /** Fastest animated navigation speed (1.5x). Above this = animation off. */
    const val MAX_NAV_SPEED = 1.5f
    /** Slider max detent: no animation, jump straight to the card. */
    const val OFF_NAV_SPEED = 1.75f
    /** Underlying scroll multiplier per displayed 1x.
     *  Old 0.5x was (0.5 * 6.25) = 3.125f. Making that the new 1.0x means base unit is 3.125f. */
    const val NAV_SPEED_UNIT = 3.125f

    /** Close animation constants */
    const val DEFAULT_CLOSE_SPEED = 1.0f
    const val MIN_CLOSE_SPEED = 0.5f
    const val MAX_CLOSE_SPEED = 1.5f
    const val OFF_CLOSE_SPEED = 1.75f

    /** Slide delay: hardcoded to 70ms. */
    const val DEFAULT_SLIDE_DELAY_MS = 70f
    const val HARDCODED_SLIDE_DELAY_MS = 70f

    // Compatibility aliases
    const val DEFAULT_SPEED = DEFAULT_NAV_SPEED
    const val MIN_SPEED = MIN_NAV_SPEED
    const val MAX_SPEED = MAX_NAV_SPEED
    const val OFF_SPEED = OFF_NAV_SPEED
    const val SPEED_UNIT = NAV_SPEED_UNIT
    /** Scale of non-centered cards. 1.0 = no shrink.
     *  Must be a valid step of sliderSideScale (valueFrom=0.2, step=0.05).
     *  0.75 is the nearest valid value; 0.74 is NOT a multiple of 0.05 from 0.2. */
    const val DEFAULT_SIDE_SCALE = 0.75f
    /** Gap between cards in dp. */
    const val DEFAULT_CARD_GAP = 8f
    /** Distance of the card strip from the bottom edge in dp. */
    const val DEFAULT_BOTTOM_GAP = 50f
    /** Base background color (RGB). Combined with opacity below. */
    const val DEFAULT_BG_COLOR = 0xFF000000.toInt()
    /** Background opacity 0 = fully transparent, 1 = solid. */
    const val DEFAULT_BG_OPACITY = 0f
    /** Height of the bottom gradient scrim in dp. 0 = no scrim. */
    const val DEFAULT_BG_GRADIENT_HEIGHT = 300f
    /** Focused-card border color (ARGB). */
    const val DEFAULT_BORDER_COLOR = 0xFF00E5FF.toInt()
    /** Card border thickness in dp (focused + unfocused). 0 = no border. */
    const val DEFAULT_BORDER_WIDTH = 2.5f
    /** Unfocused card fill (ARGB). */
    const val DEFAULT_CARD_BG = 0xFF151E2B.toInt()
    /** Focused card fill (ARGB). */
    const val DEFAULT_CARD_FOCUSED_BG = 0xFF1C283B.toInt()
    /** Unfocused card border (ARGB). */
    const val DEFAULT_CARD_BORDER = 0xFF334155.toInt()
    /** Placeholder behind previews when no snapshot/icon yet (ARGB). */
    const val DEFAULT_PREVIEW_BG = 0xFF0A0F1A.toInt()

    fun getAnimSpeed(context: Context): Float {
        return try {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_ANIM_SPEED)) {
                prefs.getFloat(KEY_ANIM_SPEED, DEFAULT_NAV_SPEED)
                    .coerceIn(MIN_NAV_SPEED, OFF_NAV_SPEED)
            } else {
                val old = prefs.getFloat("anim_speed_v3", -1f)
                when {
                    old < 0f -> DEFAULT_NAV_SPEED
                    old > 2.5f -> OFF_NAV_SPEED // was Off
                    old <= 0.6f -> DEFAULT_NAV_SPEED // was old 0.5x
                    else -> DEFAULT_NAV_SPEED
                }
            }
        } catch (ignored: Throwable) {
            DEFAULT_NAV_SPEED
        }
    }

    fun setAnimSpeed(context: Context, speed: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ANIM_SPEED, speed.coerceIn(MIN_NAV_SPEED, OFF_NAV_SPEED))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getCloseAnimSpeed(context: Context): Float {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_CLOSE_ANIM_SPEED, DEFAULT_CLOSE_SPEED)
                .coerceIn(MIN_CLOSE_SPEED, OFF_CLOSE_SPEED)
        } catch (ignored: Throwable) {
            DEFAULT_CLOSE_SPEED
        }
    }

    fun setCloseAnimSpeed(context: Context, speed: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_CLOSE_ANIM_SPEED, speed.coerceIn(MIN_CLOSE_SPEED, OFF_CLOSE_SPEED))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getSlideDelayMs(context: Context): Float {
        return HARDCODED_SLIDE_DELAY_MS
    }

    fun setSlideDelayMs(context: Context, delayMs: Float) {
        // Hardcoded to 70ms
    }

    fun slideDelayLabel(delayMs: Float): String {
        return if (delayMs <= 0f) "Instant" else "${delayMs.toInt()} ms"
    }

    /** Final slider detent past 1.5x (1.75x): skip animation, jump to the card. */
    fun isAnimOff(speed: Float): Boolean = speed >= 1.6f

    /** Final close animation slider detent past 1.5x (1.75x): skip animation. */
    fun isCloseAnimOff(speed: Float): Boolean = speed >= 1.6f

    /** Underlying scroll multiplier behind the displayed number. */
    fun effectiveSpeed(speed: Float): Float =
        (speed * NAV_SPEED_UNIT).coerceIn(0.25f, 20.0f)

    fun navSpeedLabel(speed: Float): String {
        if (isAnimOff(speed)) return "Off — no animation"
        val label = when {
            speed >= 1.5f -> "Very fast"
            speed >= 1.25f -> "Fast"
            speed >= 0.95f -> "Normal"
            speed >= 0.7f -> "Slow"
            else -> "Very slow"
        }
        val formatted = if (speed % 1f == 0f) {
            String.format("%.1fx", speed)
        } else if ((speed * 10f) % 1f == 0f) {
            String.format("%.1fx", speed)
        } else {
            String.format("%.2fx", speed)
        }
        return "$formatted — $label"
    }

    fun closeSpeedLabel(speed: Float): String {
        if (isCloseAnimOff(speed)) return "Off — no animation"
        val label = when {
            speed >= 1.5f -> "Very fast"
            speed >= 1.25f -> "Fast"
            speed >= 0.95f -> "Normal"
            speed >= 0.7f -> "Slow"
            else -> "Very slow"
        }
        val formatted = if (speed % 1f == 0f) {
            String.format("%.1fx", speed)
        } else if ((speed * 10f) % 1f == 0f) {
            String.format("%.1fx", speed)
        } else {
            String.format("%.2fx", speed)
        }
        return "$formatted — $label"
    }

    fun speedLabel(speed: Float): String = navSpeedLabel(speed)

    fun getSideScale(context: Context): Float {
        return try {
            val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_SIDE_SCALE, DEFAULT_SIDE_SCALE)
                .coerceIn(0.2f, 1.0f)
            // Snap to a valid slider step (valueFrom=0.2, step=0.05) to avoid
            // IllegalStateException on Material Slider when a stored value like
            // 0.74 is not a multiple of 0.05 from 0.2.
            snapToStep(raw, from = 0.2f, step = 0.05f).coerceIn(0.2f, 1.0f)
        } catch (ignored: Throwable) {
            DEFAULT_SIDE_SCALE
        }
    }

    /**
     * Rounds [value] to the nearest multiple of [step] starting from [from].
     * Prevents IllegalStateException when restoring a persisted preference into
     * a Material Slider whose value must be exactly on a step boundary.
     */
    private fun snapToStep(value: Float, from: Float, step: Float): Float {
        if (step <= 0f) return value
        val n = kotlin.math.round((value - from) / step)
        return from + n * step
    }


    fun setSideScale(context: Context, scale: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_SIDE_SCALE, scale.coerceIn(0.2f, 1.0f))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun scaleLabel(scale: Float): String {
        return "${(scale * 100).toInt()}%"
    }

    fun getCardGapDp(context: Context): Float {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_CARD_GAP, DEFAULT_CARD_GAP)
                .coerceIn(0f, 100f)
        } catch (ignored: Throwable) {
            DEFAULT_CARD_GAP
        }
    }

    fun setCardGapDp(context: Context, gapDp: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_CARD_GAP, gapDp.coerceIn(0f, 100f))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun gapLabel(gapDp: Float): String {
        return "${gapDp.toInt()} dp"
    }

    fun getBottomGapDp(context: Context): Float {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_BOTTOM_GAP, DEFAULT_BOTTOM_GAP)
                .coerceIn(0f, 100f)
        } catch (ignored: Throwable) {
            DEFAULT_BOTTOM_GAP
        }
    }

    fun setBottomGapDp(context: Context, gapDp: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_BOTTOM_GAP, gapDp.coerceIn(0f, 100f))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getBgColor(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_BG_COLOR, DEFAULT_BG_COLOR)
        } catch (ignored: Throwable) {
            DEFAULT_BG_COLOR
        }
    }

    fun setBgColor(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BG_COLOR, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getBgOpacity(context: Context): Float {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_BG_OPACITY, DEFAULT_BG_OPACITY)
                .coerceIn(0f, 1f)
        } catch (ignored: Throwable) {
            DEFAULT_BG_OPACITY
        }
    }

    fun setBgOpacity(context: Context, opacity: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_BG_OPACITY, opacity.coerceIn(0f, 1f))
                .apply()
        } catch (ignored: Throwable) {}
    }

    /** Base color with the chosen opacity applied as its alpha channel. */
    fun bgResultColor(context: Context): Int {
        val base = getBgColor(context)
        val alpha = (getBgOpacity(context) * 255).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (base and 0x00FFFFFF)
    }

    fun getBgGradientHeightDp(context: Context): Float {
        return DEFAULT_BG_GRADIENT_HEIGHT
    }

    fun setBgGradientHeightDp(context: Context, heightDp: Float) {
        // Hardcoded to 300dp
    }

    /** Top of the bottom scrim: same RGB as base, fully transparent. */
    fun bgGradientTopColor(context: Context): Int {
        return getBgColor(context) and 0x00FFFFFF
    }

    /** Builds the vertical bottom-up gradient drawable (transparent -> solid). */
    fun bgGradientDrawable(context: Context): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bgGradientTopColor(context), bgResultColor(context))
        )
    }

    fun opacityLabel(opacity: Float): String {
        return "${(opacity * 100).toInt()}%"
    }

    fun getBorderColor(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_BORDER_COLOR, DEFAULT_BORDER_COLOR)
        } catch (ignored: Throwable) {
            DEFAULT_BORDER_COLOR
        }
    }

    fun setBorderColor(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BORDER_COLOR, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getBorderWidthDp(context: Context): Float {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getFloat(KEY_BORDER_WIDTH, DEFAULT_BORDER_WIDTH)
                .coerceIn(0f, 8f)
        } catch (ignored: Throwable) {
            DEFAULT_BORDER_WIDTH
        }
    }

    fun setBorderWidthDp(context: Context, widthDp: Float) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_BORDER_WIDTH, widthDp.coerceIn(0f, 8f))
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun borderWidthLabel(widthDp: Float): String {
        return "${widthDp} dp"
    }

    fun getCardBg(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_CARD_BG, DEFAULT_CARD_BG)
        } catch (ignored: Throwable) {
            DEFAULT_CARD_BG
        }
    }

    fun setCardBg(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CARD_BG, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getCardFocusedBg(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_CARD_FOCUSED_BG, DEFAULT_CARD_FOCUSED_BG)
        } catch (ignored: Throwable) {
            DEFAULT_CARD_FOCUSED_BG
        }
    }

    fun setCardFocusedBg(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CARD_FOCUSED_BG, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getCardBorder(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_CARD_BORDER, DEFAULT_CARD_BORDER)
        } catch (ignored: Throwable) {
            DEFAULT_CARD_BORDER
        }
    }

    fun setCardBorder(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CARD_BORDER, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    fun getPreviewBg(context: Context): Int {
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_PREVIEW_BG, DEFAULT_PREVIEW_BG)
        } catch (ignored: Throwable) {
            DEFAULT_PREVIEW_BG
        }
    }

    fun setPreviewBg(context: Context, color: Int) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PREVIEW_BG, color)
                .apply()
        } catch (ignored: Throwable) {}
    }

    /** "#RRGGBB" (opaque) or "#AARRGGBB" when alpha != FF. */
    fun colorHex(color: Int): String {
        val alpha = (color ushr 24) and 0xFF
        return if (alpha == 0xFF) {
            String.format("#%06X", color and 0x00FFFFFF)
        } else {
            String.format("#%08X", color)
        }
    }
}
