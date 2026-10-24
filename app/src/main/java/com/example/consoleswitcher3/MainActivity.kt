package com.example.consoleswitcher3

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.consoleswitcher3.databinding.ActivityMainBinding
import com.example.consoleswitcher3.prefs.OverlayPrefs
import com.example.consoleswitcher3.shizuku.ShizukuManager
import com.example.consoleswitcher3.ui.ColorPickerDialog
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.REQUEST_CODE_SHIZUKU) {
            updateShizukuStatus()
            val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Shizuku permission granted!"
            } else {
                "Shizuku permission denied."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        setupButtons()
        setupAnimSpeed()
        setupCardAppearance()
        setupCardStyle()
        setupOverlayBackground()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        refreshAnimSpeedUi()
        refreshCardAppearanceUi()
        refreshCardStyleUi()
        refreshOverlayBackgroundUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    private fun setupButtons() {
        // Launch overlay
        binding.btnLaunchOverlay.setOnClickListener {
            val intent = Intent(this, OverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
            startActivity(intent, options.toBundle())
        }

        // Shizuku permission button
        binding.btnRequestShizuku.setOnClickListener {
            if (!ShizukuManager.isShizukuAvailable()) {
                Toast.makeText(this, "Shizuku service is not running on this device.", Toast.LENGTH_LONG).show()
                try {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                } catch (ignored: Throwable) {}
            } else if (!ShizukuManager.hasShizukuPermission()) {
                ShizukuManager.requestPermission()
            } else {
                Toast.makeText(this, "Shizuku permission is already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAnimSpeed() {
        binding.sliderAnimSpeed.setLabelFormatter { value ->
            if (OverlayPrefs.isAnimOff(value)) "Off"
            else if ((value * 10f) % 1f == 0f) String.format("%.1fx", value)
            else String.format("%.2fx", value)
        }
        binding.sliderCloseAnimSpeed.setLabelFormatter { value ->
            if (OverlayPrefs.isCloseAnimOff(value)) "Off"
            else if ((value * 10f) % 1f == 0f) String.format("%.1fx", value)
            else String.format("%.2fx", value)
        }

        refreshAnimSpeedUi()
        binding.sliderAnimSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setAnimSpeed(this, value)
                binding.tvAnimSpeedValue.text = OverlayPrefs.navSpeedLabel(value)
            }
        }
        binding.sliderCloseAnimSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setCloseAnimSpeed(this, value)
                binding.tvCloseAnimSpeedValue.text = OverlayPrefs.closeSpeedLabel(value)
            }
        }
    }

    private fun refreshAnimSpeedUi() {
        val speed = OverlayPrefs.getAnimSpeed(this)
        if (binding.sliderAnimSpeed.value != speed) {
            binding.sliderAnimSpeed.value = speed
        }
        binding.tvAnimSpeedValue.text = OverlayPrefs.navSpeedLabel(speed)

        val closeSpeed = OverlayPrefs.getCloseAnimSpeed(this)
        if (binding.sliderCloseAnimSpeed.value != closeSpeed) {
            binding.sliderCloseAnimSpeed.value = closeSpeed
        }
        binding.tvCloseAnimSpeedValue.text = OverlayPrefs.closeSpeedLabel(closeSpeed)
    }

    private fun setupCardAppearance() {
        refreshCardAppearanceUi()
        binding.sliderSideScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setSideScale(this, value)
                binding.tvSideScaleValue.text = OverlayPrefs.scaleLabel(value)
            }
        }
        binding.sliderCardGap.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setCardGapDp(this, value)
                binding.tvCardGapValue.text = OverlayPrefs.gapLabel(value)
            }
        }
        binding.sliderBottomGap.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setBottomGapDp(this, value)
                binding.tvBottomGapValue.text = OverlayPrefs.gapLabel(value)
            }
        }
    }

    private fun setupCardStyle() {
        refreshCardStyleUi()
        binding.btnPickBorder.setOnClickListener {
            ColorPickerDialog.show(this, OverlayPrefs.getBorderColor(this), "Focused border color") { color ->
                OverlayPrefs.setBorderColor(this, color)
                refreshBorderPreview()
            }
        }
        binding.btnPickCardBorder.setOnClickListener {
            ColorPickerDialog.show(this, OverlayPrefs.getCardBorder(this), "Unfocused border") { color ->
                OverlayPrefs.setCardBorder(this, color)
                refreshCardStyleUi()
            }
        }
        binding.sliderBorderWidth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setBorderWidthDp(this, value)
                binding.tvBorderWidthValue.text = OverlayPrefs.borderWidthLabel(value)
                refreshCardStyleUi()
            }
        }
        binding.btnResetColors.setOnClickListener {
            OverlayPrefs.setBorderColor(this, OverlayPrefs.DEFAULT_BORDER_COLOR)
            OverlayPrefs.setCardBorder(this, OverlayPrefs.DEFAULT_CARD_BORDER)
            OverlayPrefs.setBgColor(this, OverlayPrefs.DEFAULT_BG_COLOR)
            // Also clear retired settings keys so stale customs can't linger.
            OverlayPrefs.setCardBg(this, OverlayPrefs.DEFAULT_CARD_BG)
            OverlayPrefs.setCardFocusedBg(this, OverlayPrefs.DEFAULT_CARD_FOCUSED_BG)
            OverlayPrefs.setPreviewBg(this, OverlayPrefs.DEFAULT_PREVIEW_BG)
            refreshCardStyleUi()
            refreshOverlayBackgroundUi()
        }
    }

    private fun refreshCardStyleUi() {
        val borderWidth = OverlayPrefs.getBorderWidthDp(this)
        if (binding.sliderBorderWidth.value != borderWidth) {
            binding.sliderBorderWidth.value = borderWidth
        }
        binding.tvBorderWidthValue.text = OverlayPrefs.borderWidthLabel(borderWidth)
        refreshBorderPreview()
        val density = resources.displayMetrics.density
        binding.cardBorderPreview.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(OverlayPrefs.getCardBg(this@MainActivity))
            setStroke(
                (borderWidth * density).toInt().coerceAtLeast(0),
                OverlayPrefs.getCardBorder(this@MainActivity)
            )
        }
        binding.tvCardBorderHex.text = OverlayPrefs.colorHex(OverlayPrefs.getCardBorder(this))
    }

    private fun refreshCardAppearanceUi() {
        val scale = OverlayPrefs.getSideScale(this)
        if (binding.sliderSideScale.value != scale) {
            binding.sliderSideScale.value = scale
        }
        binding.tvSideScaleValue.text = OverlayPrefs.scaleLabel(scale)
        val gap = OverlayPrefs.getCardGapDp(this)
        if (binding.sliderCardGap.value != gap) {
            binding.sliderCardGap.value = gap
        }
        binding.tvCardGapValue.text = OverlayPrefs.gapLabel(gap)
        val bottomGap = OverlayPrefs.getBottomGapDp(this)
        if (binding.sliderBottomGap.value != bottomGap) {
            binding.sliderBottomGap.value = bottomGap
        }
        binding.tvBottomGapValue.text = OverlayPrefs.gapLabel(bottomGap)
    }

    private fun setupOverlayBackground() {
        refreshOverlayBackgroundUi()
        binding.btnPickBg.setOnClickListener {
            // Picker edits opaque RGB; opacity stays on the slider.
            val base = OverlayPrefs.getBgColor(this) or 0xFF000000.toInt()
            ColorPickerDialog.show(this, base, "Background gradient") { color ->
                OverlayPrefs.setBgColor(this, color)
                refreshBgPreview()
            }
        }
        binding.sliderBgOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                OverlayPrefs.setBgOpacity(this, value / 100f)
                binding.tvBgOpacityValue.text = OverlayPrefs.opacityLabel(value / 100f)
                refreshBgPreview()
            }
        }
    }

    private fun refreshOverlayBackgroundUi() {
        val opacity = OverlayPrefs.getBgOpacity(this)
        val percent = opacity * 100f
        if (binding.sliderBgOpacity.value != percent) {
            binding.sliderBgOpacity.value = percent
        }
        binding.tvBgOpacityValue.text = OverlayPrefs.opacityLabel(opacity)
        refreshBgPreview()
    }

    private fun refreshBgPreview() {
        binding.bgPreview.background = OverlayPrefs.bgGradientDrawable(this)
        binding.tvBgHex.text = OverlayPrefs.colorHex(OverlayPrefs.getBgColor(this))
    }

    private fun refreshBorderPreview() {
        val density = resources.displayMetrics.density
        val borderColor = OverlayPrefs.getBorderColor(this)
        val preview = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(OverlayPrefs.getCardFocusedBg(this@MainActivity))
            val strokePx = (OverlayPrefs.getBorderWidthDp(this@MainActivity) * density).toInt().coerceAtLeast(0)
            setStroke(strokePx, borderColor)
        }
        binding.borderPreview.background = preview
        binding.tvBorderHex.text = OverlayPrefs.colorHex(borderColor)
    }

    private fun updateShizukuStatus() {
        val isAvailable = ShizukuManager.isShizukuAvailable()
        val hasPerm = ShizukuManager.hasShizukuPermission()

        if (hasPerm) {
            binding.tvShizukuStatusBadge.text = "Authorized (UID ${ShizukuManager.getShizukuUid()})"
            binding.tvShizukuStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.deck_accent_green))
            binding.tvShizukuDetails.text = "Connected to Shizuku v${ShizukuManager.getShizukuVersion()}. Privileged task switching and query APIs are fully active."
            binding.btnRequestShizuku.text = "Shizuku connected"
            binding.btnRequestShizuku.isEnabled = false
        } else if (isAvailable) {
            binding.tvShizukuStatusBadge.text = "Permission required"
            binding.tvShizukuStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.deck_accent_amber))
            binding.tvShizukuDetails.text = "Shizuku service is running. Tap below to grant DeckSwitch privileged access."
            binding.btnRequestShizuku.text = "Grant Shizuku permission"
            binding.btnRequestShizuku.isEnabled = true
        } else {
            binding.tvShizukuStatusBadge.text = "Not running"
            binding.tvShizukuStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.deck_accent_red))
            binding.tvShizukuDetails.text = "Shizuku is not detected. Console Switcher will operate in limited fallback mode until Shizuku is started."
            binding.btnRequestShizuku.text = "Open Shizuku"
            binding.btnRequestShizuku.isEnabled = true
        }
    }
}