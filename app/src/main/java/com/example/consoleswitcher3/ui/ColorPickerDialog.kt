package com.example.consoleswitcher3.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * Lightweight RGB color picker dialog built on framework widgets only
 * (SeekBar/EditText/LinearLayout inside a Material dialog) — no custom
 * views, no theme-overlay requirements, so it can't crash on inflation.
 *
 * Edits the RGB of [initialColor]; the alpha channel of [initialColor]
 * is preserved in the picked result.
 */
object ColorPickerDialog {

    val PRESETS = intArrayOf(
        0xFF00E5FF.toInt(), // cyan
        0xFF00E676.toInt(), // green
        0xFFFFB300.toInt(), // amber
        0xFFFF3D71.toInt(), // red
        0xFF7C4DFF.toInt(), // purple
        0xFFFFFFFF.toInt(), // white
        0xFF000000.toInt(), // black
        0xFF090D14.toInt(), // navy
        0xFF151E2B.toInt(), // slate / card bg
        0xFF1C283B.toInt(), // focused card
        0xFF334155.toInt(), // card border
        0xFF0A0F1A.toInt(), // preview placeholder
    )

    fun show(
        context: Context,
        initialColor: Int,
        title: String = "Choose color",
        onPicked: (Int) -> Unit,
    ) {
        try {
            showInternal(context, initialColor, title, onPicked)
        } catch (t: Throwable) {
            android.util.Log.e("ColorPickerDialog", "Failed to open picker", t)
            Toast.makeText(context, "Could not open color picker.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInternal(
        context: Context,
        initialColor: Int,
        title: String,
        onPicked: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val alpha = Color.alpha(initialColor)
        var r = Color.red(initialColor)
        var g = Color.green(initialColor)
        var b = Color.blue(initialColor)

        fun currentColor(): Int =
            (alpha shl 24) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
        }
        fun refreshPreview() {
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(currentColor())
                setStroke(dp(1), 0xFF64748B.toInt())
            }
        }
        refreshPreview()
        root.addView(preview)

        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val hexInput = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(String.format("#%06X", 0xFFFFFF and initialColor))
            setSingleLine()
        }
        val hexApply = androidx.appcompat.widget.AppCompatButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            text = "Apply"
        }
        hexRow.addView(hexInput)
        hexRow.addView(hexApply)
        root.addView(hexRow)

        // Channel sliders (framework SeekBar — no Material theme dependency).
        val channelLabels = mutableMapOf<Char, TextView>()
        fun addChannel(label: Char, initial: Int, onChange: (Int) -> Unit): SeekBar {
            val tv = TextView(context).apply {
                text = "$label: $initial"
                setPadding(0, dp(12), 0, 0)
            }
            root.addView(tv)
            channelLabels[label] = tv
            val bar = SeekBar(context).apply {
                max = 255
                progress = initial.coerceIn(0, 255)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    onChange(value)
                    tv.text = "$label: $value"
                    refreshPreview()
                    hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor()))
                    hexInput.setSelection(hexInput.text.length)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            root.addView(bar)
            return bar
        }

        val rBar = addChannel('R', r) { r = it }
        val gBar = addChannel('G', g) { g = it }
        val bBar = addChannel('B', b) { b = it }

        fun syncBarsFromColor(color: Int, updateHex: Boolean = true) {
            r = Color.red(color)
            g = Color.green(color)
            b = Color.blue(color)
            // Setting progress fires listeners which refresh preview + labels.
            rBar.progress = r
            gBar.progress = g
            bBar.progress = b
            channelLabels['R']?.text = "R: $r"
            channelLabels['G']?.text = "G: $g"
            channelLabels['B']?.text = "B: $b"
            refreshPreview()
            if (updateHex) {
                hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor()))
                hexInput.setSelection(hexInput.text.length)
            }
        }

        hexApply.setOnClickListener {
            val parsed = parseHex(hexInput.text.toString())
            if (parsed == null) {
                hexInput.error = "Use #RRGGBB"
            } else {
                syncBarsFromColor((alpha shl 24) or (parsed and 0x00FFFFFF))
            }
        }

        root.addView(TextView(context).apply {
            text = "Presets"
            setPadding(0, dp(12), 0, dp(4))
        })
        // Two rows of 6 swatches using plain LinearLayouts (no GridLayout).
        var row: LinearLayout? = null
        PRESETS.forEachIndexed { index, preset ->
            if (index % 6 == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                root.addView(row)
            }
            val swatch = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(preset)
                    setStroke(dp(1), 0xFF64748B.toInt())
                }
                isClickable = true
                isFocusable = true
                contentDescription = String.format("#%06X", 0xFFFFFF and preset)
                setOnClickListener {
                    syncBarsFromColor((alpha shl 24) or (preset and 0x00FFFFFF))
                }
            }
            row?.addView(swatch)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select") { _, _ ->
                try {
                    onPicked(currentColor())
                } catch (t: Throwable) {
                    android.util.Log.e("ColorPickerDialog", "onPicked failed", t)
                    Toast.makeText(context, "Could not apply color.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    fun parseHex(raw: String): Int? {
        return try {
            val s = raw.trim().removePrefix("#")
            if (s.length == 6 || s.length == 8) {
                Color.parseColor("#$s")
            } else {
                null
            }
        } catch (ignored: Throwable) {
            null
        }
    }
}
