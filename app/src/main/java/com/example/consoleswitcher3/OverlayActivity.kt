package com.example.consoleswitcher3

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.controller.GamepadInputHelper
import com.example.consoleswitcher3.databinding.ActivityOverlayBinding
import com.example.consoleswitcher3.handheld.HandheldController
import com.example.consoleswitcher3.model.BluetoothDeviceItem
import com.example.consoleswitcher3.model.ControlCard
import com.example.consoleswitcher3.model.DeckTask
import com.example.consoleswitcher3.shizuku.ShizukuManager
import com.example.consoleswitcher3.task.TaskManager
import com.example.consoleswitcher3.ui.BluetoothDeviceAdapter
import com.example.consoleswitcher3.ui.DeckControlAdapter
import com.example.consoleswitcher3.ui.DeckTaskAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class OverlayActivity : AppCompatActivity(), GamepadInputHelper.GamepadListener {

    private enum class OverlayMode {
        APP_SWITCHER,
        CONTROL_CENTER
    }

    private enum class ActiveControlModal {
        NONE,
        BLUETOOTH,
        WIFI,
        HANDHELD_MODE
    }

    private lateinit var binding: ActivityOverlayBinding
    private lateinit var gamepadHelper: GamepadInputHelper
    private lateinit var taskAdapter: DeckTaskAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var controlAdapter: DeckControlAdapter
    private lateinit var controlLayoutManager: LinearLayoutManager
    private lateinit var bluetoothDeviceAdapter: BluetoothDeviceAdapter
    private lateinit var bluetoothLayoutManager: LinearLayoutManager

    private var currentMode: OverlayMode = OverlayMode.APP_SWITCHER
    private var isTransitioningMode: Boolean = false
    private var activeControlModal: ActiveControlModal = ActiveControlModal.NONE
    private var activeHandheldModalControlId: String? = null
    private val isControlModalOpen: Boolean
        get() = activeControlModal != ActiveControlModal.NONE
    private val isBluetoothMenuOpen: Boolean
        get() = activeControlModal == ActiveControlModal.BLUETOOTH
    private val isWifiMenuOpen: Boolean
        get() = activeControlModal == ActiveControlModal.WIFI
    private val isHandheldModalOpen: Boolean
        get() = activeControlModal == ActiveControlModal.HANDHELD_MODE

    private val busyBluetoothAddresses = mutableMapOf<String, String>()
    private val busyBluetoothTimestamps = mutableMapOf<String, Long>()
    private val errorBluetoothAddresses = mutableSetOf<String>()
    private val bluetoothProfiles: Map<Int, BluetoothProfile>
        get() = sharedBluetoothProfiles

    private data class SavedWifiEntry(
        val networkId: Int,
        val ssid: String
    )
    private val busyWifiNetworks = mutableMapOf<String, String>()
    private val errorWifiNetworks = mutableSetOf<String>()
    private var cachedShellWifiNetworks: List<SavedWifiEntry> = emptyList()
    private var cachedShellConnectedSsid: String? = null
    private var cachedShellConnectedNetId: Int = -1
    private var isRefreshingShellWifi: Boolean = false

    /** Target of the in-flight programmatic scroll, if any. While set,
     *  navigation chains off it (not the last committed position) so rapid
     *  gamepad presses don't collapse onto a stale index. Cleared on settle. */
    private var pendingTarget: Int? = null
    /** True while our own smooth-scroll is running; the idle handler then
     *  commits the landing instead of treating it as a user drag. */
    private var programmaticScroll = false
    /** While true, IDLE callbacks do nothing (transforms still refresh via
     *  onScrolled/posts). Used around stopScroll()+jump in the kill path so a
     *  synchronously-dispatched idle can't commit transient state mid-fix. */
    private var suppressIdleCommit = false

    private var controlPendingTarget: Int? = null
    private var controlProgrammaticScroll = false

    private var tasks = mutableListOf<DeckTask>()
    private var controlCards = mutableListOf<ControlCard>()
    private var vibrator: Vibrator? = null
    private var sideScale = com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_SIDE_SCALE

    /** Keeps closed cards on screen while they fly out (their data is already gone). */
    private val taskRemovalAnimator = com.example.consoleswitcher3.ui.TaskRemovalItemAnimator()
    private var removalShiftX: Float = 0f
    private var removalShiftStartIndex: Int = -1
    private var removalLeftShiftX: Float = 0f
    private var removalLeftShiftEndIndex: Int = -1
    private var pendingTargetIndexAfterRemove: Int = -1
    /** Collapse to start in the layout pass that applies a removal (see onLayoutCompleted). */
    private var pendingCollapse: PendingCollapse? = null
    /** Slides the surviving cards from where they were on screen into their new slots. */
    private var collapseAnimator: ValueAnimator? = null
    /** The last task was closed: dismiss once its fly-out finishes. */
    private var dismissAfterLastFlyOut = false
    private var modeTransitionAnimator: ValueAnimator? = null
    private var isEntranceAnimating: Boolean = false
    private var isWaitingForEntranceLayout: Boolean = false
    private var hasPlayedEntranceAnimation: Boolean = false
    private var currentEntranceCenterPos: Int = 0
    private var currentEntranceStartY: Float = 0f
    private var entranceGeneration: Int = 0
    private var entrancePendingCount: Int = 0
    private var entranceSafetyRunnable: Runnable? = null
    private val entranceAnimators = mutableListOf<android.view.ViewPropertyAnimator>()

    private lateinit var audioManager: AudioManager
    private var currentBrightness: Int = 128
    private val indicatorHandler = Handler(Looper.getMainLooper())
    private var lastReportedVolume: Int = -1

    private val screenRecordLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val appContext = applicationContext
            dismissSwitcher()
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                delay(200L)
                val serviceIntent = Intent(
                    appContext,
                    com.example.consoleswitcher3.service.ScreenRecordService::class.java
                ).apply {
                    action = com.example.consoleswitcher3.service.ScreenRecordService.ACTION_START
                    putExtra(
                        com.example.consoleswitcher3.service.ScreenRecordService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )
                    putExtra(
                        com.example.consoleswitcher3.service.ScreenRecordService.EXTRA_RESULT_DATA,
                        data
                    )
                }
                androidx.core.content.ContextCompat.startForegroundService(appContext, serviceIntent)
            }
        }
    }

    private val hideBrightnessRunnable = Runnable {
        binding.layoutBrightness.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.layoutBrightness.visibility = View.GONE
                binding.layoutBrightness.alpha = 1f
            }
            .start()
    }

    private val hideVolumeRunnable = Runnable {
        binding.layoutVolume.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.layoutVolume.visibility = View.GONE
                binding.layoutVolume.alpha = 1f
            }
            .start()
    }

    private val hideMediaRunnable = Runnable {
        binding.layoutMedia.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.layoutMedia.visibility = View.GONE
                binding.layoutMedia.alpha = 1f
            }
            .start()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val currentVol = getVolumePercent()
            if (lastReportedVolume >= 0 && currentVol != lastReportedVolume) {
                lastReportedVolume = currentVol
                showVolumeIndicator()
            } else {
                lastReportedVolume = currentVol
            }
        }
    }

    companion object {
        private const val TAG = "OverlayActivity"
        private const val BRIGHTNESS_STEP = 13 // ~5% of 255
        private const val MIN_BRIGHTNESS = 10
        private const val MAX_BRIGHTNESS = 255
        private const val INDICATOR_TIMEOUT_MS = 2000L
        private const val PENDING_TOGGLE_TIMEOUT_MS = 4000L
        private const val REQUEST_CODE_BLUETOOTH_PERMS = 2002
        private const val PROFILE_HID_HOST = 4 // BluetoothProfile.HID_HOST / INPUT_DEVICE
        private const val ACTION_BLUETOOTH_SETTINGS = "__bluetooth_settings__"
        private const val ACTION_WIFI_SETTINGS = "__wifi_settings__"
        private const val RECENTLY_CLOSED_TTL_MS = 4000L

        /** Outlives the overlay so dismissing right after a close never cancels force-stop. */
        private val killScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
        )
        private val recentlyClosedTasks = ConcurrentHashMap<String, Long>()

        private val sharedBluetoothProfiles = ConcurrentHashMap<Int, BluetoothProfile>()
        private val requestedBluetoothProfiles = ConcurrentHashMap.newKeySet<Int>()
        @Volatile
        private var activeOverlayRef: WeakReference<OverlayActivity>? = null

        private val sharedProfileListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                sharedBluetoothProfiles[profile] = proxy
                val activity = activeOverlayRef?.get()
                if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                    if (activity.isBluetoothMenuOpen) {
                        activity.refreshBluetoothMenuUi()
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                sharedBluetoothProfiles.remove(profile)
                requestedBluetoothProfiles.remove(profile)
            }
        }
    }

    private data class PendingToggle(
        val targetState: Boolean,
        val timestamp: Long
    )
    private val pendingToggleStates = mutableMapOf<String, PendingToggle>()

    private val controlCenterReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    val pending = pendingToggleStates["wifi"]
                    if (pending != null) {
                        if (pending.targetState && state == WifiManager.WIFI_STATE_ENABLED) {
                            pendingToggleStates.remove("wifi")
                        } else if (!pending.targetState && state == WifiManager.WIFI_STATE_DISABLED) {
                            pendingToggleStates.remove("wifi")
                        }
                    }
                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        busyWifiNetworks.clear()
                        errorWifiNetworks.clear()
                    }
                    refreshControlCards()
                    if (isWifiMenuOpen) {
                        refreshWifiMenuUi()
                    }
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    if (intent.action == WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) {
                        val supplicantError = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                        if (supplicantError != -1) {
                            val iter = busyWifiNetworks.entries.iterator()
                            while (iter.hasNext()) {
                                val entry = iter.next()
                                if (entry.value == "Connecting...") {
                                    errorWifiNetworks.add(entry.key)
                                    iter.remove()
                                }
                            }
                        }
                    }
                    if (isWifiMenuOpen) {
                        refreshWifiMenuUi()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val pending = pendingToggleStates["bluetooth"]
                    if (pending != null) {
                        if (pending.targetState && state == BluetoothAdapter.STATE_ON) {
                            pendingToggleStates.remove("bluetooth")
                        } else if (!pending.targetState && state == BluetoothAdapter.STATE_OFF) {
                            pendingToggleStates.remove("bluetooth")
                        }
                    }
                    if (state == BluetoothAdapter.STATE_ON) {
                        initBluetoothProfiles()
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        busyBluetoothAddresses.clear()
                        busyBluetoothTimestamps.clear()
                        errorBluetoothAddresses.clear()
                    }
                    refreshControlCards()
                    if (isBluetoothMenuOpen) {
                        refreshBluetoothMenuUi()
                    }
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.hid.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED" -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val addr = device?.address
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        .takeIf { it != -1 }
                        ?: intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)

                    if (addr != null) {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                busyBluetoothAddresses.remove(addr)
                                busyBluetoothTimestamps.remove(addr)
                                errorBluetoothAddresses.remove(addr)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (!isDeviceConnected(device) && !isDeviceConnecting(device)) {
                                    val wasConnecting = busyBluetoothAddresses[addr] == "Connecting..."
                                    val startedAt = busyBluetoothTimestamps[addr] ?: 0L
                                    if (!wasConnecting || SystemClock.uptimeMillis() - startedAt > 1200L) {
                                        if (wasConnecting) {
                                            errorBluetoothAddresses.add(addr)
                                        }
                                        busyBluetoothAddresses.remove(addr)
                                        busyBluetoothTimestamps.remove(addr)
                                    }
                                }
                            }
                            BluetoothProfile.STATE_CONNECTING -> {
                                if (busyBluetoothAddresses[addr] != "Disconnecting...") {
                                    busyBluetoothAddresses[addr] = "Connecting..."
                                    errorBluetoothAddresses.remove(addr)
                                    if (!busyBluetoothTimestamps.containsKey(addr)) {
                                        busyBluetoothTimestamps[addr] = SystemClock.uptimeMillis()
                                    }
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTING -> {
                                busyBluetoothAddresses[addr] = "Disconnecting..."
                                busyBluetoothTimestamps[addr] = SystemClock.uptimeMillis()
                            }
                        }
                        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                            if (busyBluetoothAddresses[addr] == "Connecting...") {
                                errorBluetoothAddresses.add(addr)
                            }
                            busyBluetoothAddresses.remove(addr)
                            busyBluetoothTimestamps.remove(addr)
                        }
                    }
                    if (isBluetoothMenuOpen) {
                        refreshBluetoothMenuUi()
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    pendingToggleStates.remove("dnd")
                    refreshControlCards()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    pendingToggleStates.remove("battery_saver")
                    refreshControlCards()
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    pendingToggleStates.remove("airplane")
                    refreshControlCards()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Edge-to-edge drawing without ever changing system-bar visibility,
        // so the app underneath never relayouts when the overlay opens/closes.
        // Status/nav colors stay transparent via Theme.ConsoleSwitcher.Overlay.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Clear window dimming for transparent background
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        binding = ActivityOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Adjust indicator margins based on system insets (cutouts/navigation bars)
        ViewCompat.setOnApplyWindowInsetsListener(binding.overlayRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val baseMarginBottom = (18 * resources.displayMetrics.density).toInt()
            val baseMarginSide = (28 * resources.displayMetrics.density).toInt()

            (binding.layoutBrightness.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = baseMarginSide + bars.left
                it.bottomMargin = baseMarginBottom + bars.bottom
                binding.layoutBrightness.layoutParams = it
            }

            (binding.layoutVolume.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = baseMarginSide + bars.left
                it.bottomMargin = baseMarginBottom + bars.bottom
                binding.layoutVolume.layoutParams = it
            }

            (binding.layoutMedia.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = baseMarginSide + bars.left
                it.bottomMargin = baseMarginBottom + bars.bottom
                binding.layoutMedia.layoutParams = it
            }

            insets
        }

        // Card strip height above the bottom edge comes from Settings.
        val bottomGapPx = (com.example.consoleswitcher3.prefs.OverlayPrefs.getBottomGapDp(this) *
                resources.displayMetrics.density).toInt()
        (binding.rvTasks.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = bottomGapPx
            binding.rvTasks.layoutParams = it
        }
        (binding.rvControlCenter.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = bottomGapPx
            binding.rvControlCenter.layoutParams = it
        }
        (binding.emptyLayout.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = bottomGapPx + (10 * resources.displayMetrics.density).toInt()
            binding.emptyLayout.layoutParams = it
        }

        // Bottom gradient scrim instead of a flat overlay background:
        // transparent at top, fading to the chosen color at the bottom edge.
        applyBottomGradient()

        // Navigation here is fully programmatic (selectedPosition + scale/border),
        // so no view may ever hold view-focus: any focused state would paint the
        // grey selectableItemBackground / selector veil (e.g. a fullscreen parent
        // catching a focus search). Block it window-wide and evict stragglers.
        (window.decorView as? android.view.ViewGroup)?.descendantFocusability =
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            newFocus?.clearFocus()
        }
        binding.root.clearFocus()

        initVibrator()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        initBrightness()
        lastReportedVolume = getVolumePercent()
        gamepadHelper = GamepadInputHelper(this)

        setupRecyclerView()
        setupControlRecyclerView()
        setupBluetoothMenu()
        initBluetoothProfiles()
        setupListeners()
        loadControlCards()
        loadTasks()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun applyBottomGradient() {
        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val heightPx = (prefs.getBgGradientHeightDp(this) *
                resources.displayMetrics.density).toInt()
        if (heightPx <= 0) {
            binding.scrim.visibility = View.GONE
            return
        }
        val params = binding.scrim.layoutParams
        params.height = heightPx
        binding.scrim.layoutParams = params
        binding.scrim.background = prefs.bgGradientDrawable(this)
        binding.scrim.visibility = View.VISIBLE
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        } catch (ignored: Throwable) {}
    }

    private fun setupRecyclerView() {
        // The centering padding from updateCenterPadding() (~half the screen
        // per side) hides side cards from LinearLayoutManager's layout
        // window: it only lays out children starting inside
        // [paddingStart, width - paddingEnd]. Without extra space, on open
        // only the centered card (+1) exists and side cards pop in only
        // when near center. Extend the window past the padding so the
        // neighbors on both sides are always laid out.
        val extraLayoutPx = resources.displayMetrics.widthPixels
        layoutManager = object : LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace)
                extraLayoutSpace[0] += extraLayoutPx
                extraLayoutSpace[1] += extraLayoutPx
            }

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                if (state?.isPreLayout == true) return
                if (binding.rvTasks.height > 0 && binding.rvTasks.minimumHeight != binding.rvTasks.height) {
                    binding.rvTasks.minimumHeight = binding.rvTasks.height
                }
                // When an item is removed, RecyclerView may anchor to the first
                // visible child and clamp excess scroll at the strip boundaries,
                // shifting surviving cards away from exact center. Compensate
                // synchronously inside the layout pass so the target card is
                // locked to rvTasks.width / 2f before any frame is drawn.
                val targetIdx = if (pendingTargetIndexAfterRemove >= 0) {
                    pendingTargetIndexAfterRemove
                } else if (tasks.size == 1) {
                    0
                } else {
                    -1
                }
                pendingTargetIndexAfterRemove = -1
                if (targetIdx >= 0) {
                    val targetView = findViewByPosition(targetIdx)
                    if (targetView != null && targetView.width > 0) {
                        val currentCenter = (targetView.left + targetView.right) / 2f
                        val centerX = binding.rvTasks.width / 2f
                        val dx = Math.round(centerX - currentCenter)
                        if (dx != 0) {
                            binding.rvTasks.offsetChildrenHorizontal(dx)
                        }
                    }
                }
                // A removal was just applied: survivors now sit in their final
                // slots, so start the collapse from where they were on screen.
                pendingCollapse?.let {
                    pendingCollapse = null
                    startCollapse(it)
                }
                updateXmbTransforms()
            }
        }
        binding.rvTasks.layoutManager = layoutManager

        // No default item animations (they blink on navigation): this animator only
        // keeps closed cards alive while they fly out, everything else is instant.
        binding.rvTasks.itemAnimator = taskRemovalAnimator
        binding.rvTasks.addRecyclerListener { holder -> taskRemovalAnimator.onViewRecycled(holder) }
        binding.rvTasks.overScrollMode = View.OVER_SCROLL_NEVER

        sideScale = com.example.consoleswitcher3.prefs.OverlayPrefs.getSideScale(this)
        taskAdapter = DeckTaskAdapter(
            tasks = tasks,
            cardWidthPx = getCardWidthPx(),
            cardHeightPx = getCardHeightPx(),
            cardGapPx = getCardGapPx(),
            sideScale = sideScale,
            onTaskClick = { clickedPosition, task ->
                if (!dismissAfterLastFlyOut) {
                    val current = pendingTarget ?: taskAdapter.selectedPosition
                    if (clickedPosition == current) {
                        finishEntranceAnimation()
                        switchTask(task)
                    } else {
                        smoothScrollToCenter(clickedPosition)
                    }
                }
            }
        )
        binding.rvTasks.adapter = taskAdapter

        // A recycled view re-entering the viewport (e.g. scrolling back to a
        // card that went off-screen) attaches without necessarily producing a
        // scroll delta, so the onScrolled loop below may never correct it.
        // Give it its transform right away; post a second pass as a fallback
        // for the case where it isn't laid out yet (left/right still 0).
        binding.rvTasks.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    onEntranceChildAttached(binding.rvTasks, layoutManager, view)
                    if (view.width > 0 && view.right > view.left) {
                        applyXmbTransform(view)
                    } else {
                        view.post {
                            if (view.parent != null) applyXmbTransform(view)
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (isEntranceAnimating) {
                        view.animate().cancel()
                        view.translationY = 0f
                        view.alpha = 1f
                    }
                }
            }
        )

        // Continuous XMB scale transforms as user scrolls (full opacity on all cards).
        // Centering is owned solely by smoothScrollToCenter() below: a SnapHelper
        // used to fight it (two smooth-scrolls chasing center = end-of-scroll
        // jumps). On idle after a user drag we snap manually on the same path.
        binding.rvTasks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateXmbTransforms()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (suppressIdleCommit) return
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // User grabbed the strip mid-animation: our scroller is dead,
                    // so its target must die too. Otherwise the next idle commits
                    // a stale index and selection desyncs from the visual center
                    // (a later kill would then operate on the wrong card).
                    programmaticScroll = false
                    pendingTarget = null
                    return
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (programmaticScroll) {
                    programmaticScroll = false
                    val target = pendingTarget?.takeIf { it in 0 until taskAdapter.itemCount }
                    pendingTarget = null
                    val landed = target
                        ?: findCenterPosition()?.takeIf { it in 0 until taskAdapter.itemCount }
                    android.util.Log.d(TAG, "idle commit: landed=$landed target=$target selected=${taskAdapter.selectedPosition}")
                    if (landed != null && landed != RecyclerView.NO_POSITION) {
                        if (landed != taskAdapter.selectedPosition) {
                            taskAdapter.selectedPosition = landed
                            // Buzz only if we landed somewhere we didn't buzz
                            // for at nav start (start already buzzed for target).
                            if (landed != target) triggerHaptic()
                        }
                    }
                    // One final pass so side cards rest at exactly sideScale.
                    updateXmbTransforms()
                } else {
                    // User drag/fling ended: glide the nearest card to center
                    // on the same scroller instead of snapping via a helper.
                    val nearest = findCenterPosition()
                    if (nearest != null && nearest != RecyclerView.NO_POSITION) {
                        val child = layoutManager.findViewByPosition(nearest)
                        val childCenter = if (child != null) (child.left + child.right) / 2f else 0f
                        val centerX = binding.rvTasks.width / 2f
                        val offCenter = if (child != null) {
                            kotlin.math.abs(centerX - childCenter)
                        } else Float.MAX_VALUE
                        val canScrollMore = if (childCenter > centerX) {
                            recyclerView.canScrollHorizontally(1)
                        } else {
                            recyclerView.canScrollHorizontally(-1)
                        }
                        if (offCenter > 2f && canScrollMore) {
                            smoothScrollToCenter(nearest)
                        } else if (nearest != taskAdapter.selectedPosition) {
                            taskAdapter.selectedPosition = nearest
                            triggerHaptic()
                            updateXmbTransforms()
                        } else {
                            updateXmbTransforms()
                        }
                    } else {
                        updateXmbTransforms()
                    }
                }
            }
        })

        // Dynamically calculate horizontal padding to ensure first and last items center perfectly
        binding.rvTasks.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCenterPadding()
            binding.rvTasks.post { updateXmbTransforms() }
        }
    }

    private fun getCardGapPx(): Int {
        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val raw = (prefs.getCardGapDp(this) * resources.displayMetrics.density).toInt()
        return if (raw % 2 == 0) raw else raw + 1
    }

    private fun getCardHeightPx(): Int {
        val density = resources.displayMetrics.density
        return (180f * density).toInt()
    }

    private fun getCardWidthPx(): Int {
        val metrics = resources.displayMetrics
        val screenW = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val screenH = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val aspectRatio = if (screenH > 0f) (screenW / screenH).coerceIn(1.0f, 2.5f) else (16f / 9f)
        return (getCardHeightPx() * aspectRatio).toInt()
    }

    private fun getItemWidthPx(): Int {
        return getCardWidthPx() + getCardGapPx()
    }

    private fun getControlCardHeightPx(): Int {
        return getCardHeightPx()
    }

    private fun getControlCardWidthPx(): Int {
        // Control Center cards are square
        return getControlCardHeightPx()
    }

    private fun getControlItemWidthPx(): Int {
        return getControlCardWidthPx() + getCardGapPx()
    }

    /** Layout position of the child nearest the RecyclerView center, or null. */
    private fun findCenterPosition(): Int? {
        if (binding.rvTasks.width <= 0 || taskAdapter.itemCount == 0) return null
        val centerX = binding.rvTasks.width / 2f
        var bestPos: Int? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until binding.rvTasks.childCount) {
            val child = binding.rvTasks.getChildAt(i) ?: continue
            if (taskRemovalAnimator.isFlyingOut(child)) continue
            if (child.right <= child.left) continue
            val childCenter = (child.left + child.right) / 2f
            val dist = kotlin.math.abs(centerX - childCenter)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = layoutManager.getPosition(child)
            }
        }
        return bestPos
    }

    private fun updateCenterPadding() {
        val metrics = resources.displayMetrics
        val fallbackWidth = maxOf(metrics.widthPixels, metrics.heightPixels)
        val rvWidth = if (binding.rvTasks.width > 0) binding.rvTasks.width else fallbackWidth
        if (rvWidth > 0) {
            val itemW = getItemWidthPx()
            val padding = ((rvWidth - itemW) / 2).coerceAtLeast(0)
            if (binding.rvTasks.paddingStart != padding || binding.rvTasks.paddingEnd != padding) {
                binding.rvTasks.setPadding(padding, 0, padding, 0)
                binding.rvTasks.clipToPadding = false
                binding.rvTasks.clipChildren = false
            }
        }
    }

    /**
     * Applies discrete XMB layout transforms:
     * - The centered card is scaled to 1.0x with maximum elevation.
     * - Every other card uses exactly sideScale, no matter how far out it is,
     *   so items beyond the immediate neighbours never get smaller/farther.
     * - The shrink-to-sideScale transition runs over one card width and starts
     *   immediately as the card leaves center, so scrolling still animates
     *   smoothly, but at rest all sides are uniform.
     * - Bottom pivot keeps every card's bottom edge pinned to a straight baseline.
     * - translationX fully compensates the shrink ((2k-1)*extra for the k-th
     *   card out) so the visible gap stays uniform instead of growing with distance.
     */
    private fun updateXmbTransforms() {
        if (binding.rvTasks.width <= 0) return

        for (i in 0 until binding.rvTasks.childCount) {
            val child = binding.rvTasks.getChildAt(i) ?: continue
            applyXmbTransform(child)
        }
    }

    /** Per-card XMB transform for one attached child (see updateXmbTransforms). */
    private fun applyXmbTransform(child: View) {
        if (taskRemovalAnimator.isFlyingOut(child)) return
        if (child.right <= child.left || child.height <= 0) return
        val centerX = binding.rvTasks.width / 2f
        if (centerX <= 0f) return

        val pos = layoutManager.getPosition(child)
        val shift = removalShiftFor(pos)

        val childCenter = (child.left + child.right) / 2f
        val visualCenter = childCenter + shift
        val signed = centerX - visualCenter
        val distance = Math.abs(signed)

            // Pin scale pivot to the card's bottom-center (not the item bottom,
            // which now includes the 16dp gap + label row) so every card's
            // bottom edge stays pinned to a straight baseline. getBottom() is
            // already relative to the padded parent: don't add padding again.
            if (child.width > 0 && child.height > 0) {
                child.pivotX = child.width / 2f
                val cardBottom = child.findViewById<View>(R.id.cardContainer)?.bottom
                child.pivotY = if (cardBottom != null && cardBottom > 0) {
                    cardBottom.toFloat().coerceAtMost(child.height.toFloat())
                } else {
                    child.height.toFloat()
                }
            }

            val cardWidth = child.width.toFloat()
            if (cardWidth <= 0f) return

            // Shrink starts immediately as the card leaves center and is fully
            // shrunk once one full card away. Everything beyond that stays at
            // exactly sideScale.
            val fraction = (distance / cardWidth).coerceIn(0f, 1f)

            // Discrete-at-rest scale: 1.0f at center, sideScale everywhere else.
            val scale = 1.0f - (fraction * (1.0f - sideScale))
            child.scaleX = scale
            child.scaleY = scale

            // Full gap compensation without a dead zone: the k-th card out sits
            // at (2k-1)*extra so the visible gap stays uniform. Between rest
            // positions interpolate linearly â€” slope 1x from center to first
            // neighbour, 2x beyond â€” so motion starts immediately, no late kick.
            val extra = cardWidth * (1.0f - sideScale) / 2f
            val pull = if (distance <= cardWidth) {
                distance / cardWidth
            } else {
                (2f * distance / cardWidth) - 1f
            }
            child.translationX = Math.signum(signed) * extra * pull + shift

            // Full opacity for every card — focus is shown by size/border only (except during open entrance)
            if (!isEntranceAnimating && child.alpha != 1f) child.alpha = 1f

            // Center card elevated above neighbors. Discrete (not continuous):
            // animating translationZ every frame rebuilds CardView shadows and
            // reads as flicker on some GPUs.
            val wantZ = if (distance < (child.width * 0.35f)) 20f else 0f
            if (child.translationZ != wantZ) child.translationZ = wantZ

            // Update focused border and action prompt for the center item
            val isCenter = distance < (child.width * 0.35f)
            val holder = binding.rvTasks.getChildViewHolder(child) as? DeckTaskAdapter.TaskViewHolder
            holder?.setCenterFocused(isCenter)
    }

    private fun setupControlRecyclerView() {
        val extraLayoutPx = resources.displayMetrics.widthPixels
        controlLayoutManager = object : LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace)
                extraLayoutSpace[0] += extraLayoutPx
                extraLayoutSpace[1] += extraLayoutPx
            }

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                updateControlXmbTransforms()
            }
        }
        binding.rvControlCenter.layoutManager = controlLayoutManager
        binding.rvControlCenter.itemAnimator = null
        binding.rvControlCenter.overScrollMode = View.OVER_SCROLL_NEVER

        controlAdapter = DeckControlAdapter(
            cards = controlCards,
            cardWidthPx = getControlCardWidthPx(),
            cardHeightPx = getControlCardHeightPx(),
            cardGapPx = getCardGapPx(),
            sideScale = sideScale,
            onCardClick = { clickedPosition, _ ->
                val current = controlPendingTarget ?: controlAdapter.selectedPosition
                if (clickedPosition == current) {
                    if (!isTransitioningMode) {
                        finishEntranceAnimation()
                        openControlMenuAt(clickedPosition)
                    }
                } else {
                    smoothScrollControlToCenter(clickedPosition)
                }
            }
        )
        binding.rvControlCenter.adapter = controlAdapter

        updateControlCenterPadding()

        binding.rvControlCenter.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    onEntranceChildAttached(binding.rvControlCenter, controlLayoutManager, view)
                    if (view.width > 0 && view.right > view.left) {
                        applyControlXmbTransform(view)
                    } else {
                        view.post {
                            if (view.parent != null) applyControlXmbTransform(view)
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (isEntranceAnimating) {
                        view.animate().cancel()
                        view.translationY = 0f
                        view.alpha = 1f
                    }
                }
            }
        )

        binding.rvControlCenter.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateControlXmbTransforms()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (suppressIdleCommit) return
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    controlProgrammaticScroll = false
                    controlPendingTarget = null
                    return
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (controlProgrammaticScroll) {
                    controlProgrammaticScroll = false
                    val target = controlPendingTarget?.takeIf { it in 0 until controlAdapter.itemCount }
                    controlPendingTarget = null
                    val landed = target
                        ?: findControlCenterPosition()?.takeIf { it in 0 until controlAdapter.itemCount }
                    if (landed != null && landed != RecyclerView.NO_POSITION) {
                        if (landed != controlAdapter.selectedPosition) {
                            controlAdapter.selectedPosition = landed
                            if (landed != target) triggerHaptic()
                        }
                    }
                    updateControlXmbTransforms()
                } else {
                    val nearest = findControlCenterPosition()
                    if (nearest != null && nearest != RecyclerView.NO_POSITION) {
                        val child = controlLayoutManager.findViewByPosition(nearest)
                        val childCenter = if (child != null) (child.left + child.right) / 2f else 0f
                        val centerX = binding.rvControlCenter.width / 2f
                        val offCenter = if (child != null) {
                            kotlin.math.abs(centerX - childCenter)
                        } else Float.MAX_VALUE
                        val canScrollMore = if (childCenter > centerX) {
                            recyclerView.canScrollHorizontally(1)
                        } else {
                            recyclerView.canScrollHorizontally(-1)
                        }
                        if (offCenter > 2f && canScrollMore) {
                            smoothScrollControlToCenter(nearest)
                        } else if (nearest != controlAdapter.selectedPosition) {
                            controlAdapter.selectedPosition = nearest
                            triggerHaptic()
                            updateControlXmbTransforms()
                        } else {
                            updateControlXmbTransforms()
                        }
                    } else {
                        updateControlXmbTransforms()
                    }
                }
            }
        })

        binding.rvControlCenter.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateControlCenterPadding()
            binding.rvControlCenter.post { updateControlXmbTransforms() }
        }
    }

    private fun loadControlCards() {
        controlCards.clear()
        controlCards.addAll(buildControlCards())
        controlAdapter.updateCards(controlCards)
        controlAdapter.selectedPosition = 0
        controlPendingTarget = null
        controlProgrammaticScroll = false
        binding.rvControlCenter.post {
            updateControlCenterPadding()
            jumpControlToCenter(0)
        }
    }

    private fun refreshControlCards() {
        if (!::controlAdapter.isInitialized) return
        val updated = buildControlCards()
        val idsChanged = controlCards.size != updated.size ||
            controlCards.indices.any { controlCards[it].id != updated[it].id }
        if (idsChanged) {
            controlCards.clear()
            controlCards.addAll(updated)
            controlAdapter.updateCards(controlCards)
            if (controlCards.isNotEmpty()) {
                val safePos = controlAdapter.selectedPosition.coerceIn(0, controlCards.lastIndex)
                controlAdapter.selectedPosition = safePos
                if (!isTransitioningMode) {
                    binding.rvControlCenter.post {
                        updateControlCenterPadding()
                        jumpControlToCenter(safePos)
                    }
                }
            }
            return
        }
        for (i in updated.indices) {
            if (controlCards[i] != updated[i]) {
                controlCards[i] = updated[i]
                controlAdapter.updateCardAt(i, updated[i])
            }
        }
    }

    private fun buildControlCards(): List<ControlCard> {
        val enabledIds = com.example.consoleswitcher3.prefs.OverlayPrefs.getEnabledControlPanelIds(this)
        return enabledIds.map { id ->
            if (HandheldController.isHandheldControl(id)) {
                buildHandheldControlCard(id, HandheldController.readCurrentValue(this, id))
            } else {
                val state = when (id) {
                    "wifi" -> isWifiEnabled()
                    "bluetooth" -> isBluetoothEnabled()
                    "dnd" -> isDndEnabled()
                    "dark_mode" -> isDarkModeEnabled()
                    "rotation" -> isRotationAutoEnabled()
                    "battery_saver" -> isBatterySaverEnabled()
                    "night_light" -> isNightLightEnabled()
                    "screen_record" -> com.example.consoleswitcher3.shizuku.ScreenRecordHelper.isRecording
                    "airplane" -> isAirplaneModeEnabled()
                    "screen_timeout" -> isScreenTimeoutKeepAwake()
                    "screenshot" -> true
                    else -> false
                }
                buildControlCardForState(id, state)
            }
        }
    }

    private fun getHandheldModeIconRes(id: String, value: Int): Int {
        if (id == HandheldController.ID_PERF_MODE) {
            return when (value) {
                0 -> R.drawable.ic_directions_walk_rounded
                1 -> R.drawable.ic_directions_run_rounded
                2 -> R.drawable.ic_sprint_rounded
                else -> R.drawable.ic_directions_walk_rounded
            }
        }
        if (id == HandheldController.ID_FAN_MODE) {
            return if (value == 0) R.drawable.ic_mode_fan_off_rounded else R.drawable.ic_mode_fan_rounded
        }
        return when (id) {
            HandheldController.ID_JOYSTICK_LED -> R.drawable.ic_joystick_rounded
            HandheldController.ID_BYPASS_CHARGING -> R.drawable.ic_power_plug_rounded
            HandheldController.ID_CONTROLLER_STYLE -> R.drawable.ic_gamepad_rounded
            HandheldController.ID_TRIGGER_MODE -> R.drawable.ic_game_button_r2_rounded
            else -> com.example.consoleswitcher3.prefs.OverlayPrefs.controlItemIconRes(id)
        }
    }

    private fun buildHandheldControlCard(id: String, value: Int): ControlCard {
        val (title, isActive) = HandheldController.resolveCardState(id, value)
        return ControlCard(
            id = id,
            title = title,
            cardIconRes = getHandheldModeIconRes(id, value),
            isEnabled = isActive
        )
    }

    private fun buildControlCardForState(id: String, enabled: Boolean): ControlCard {
        val iconRes = when (id) {
            "wifi" -> if (enabled) R.drawable.ic_wifi_rounded else R.drawable.ic_wifi_off_rounded
            "bluetooth" -> if (enabled) R.drawable.ic_bluetooth_rounded else R.drawable.ic_bluetooth_disabled_rounded
            "dnd" -> if (enabled) R.drawable.ic_dnd_rounded else R.drawable.ic_dnd_off_rounded
            "dark_mode" -> R.drawable.ic_contrast_rounded
            "rotation" -> if (enabled) R.drawable.ic_screen_rotation_rounded else R.drawable.ic_screen_lock_rotation_rounded
            "battery_saver" -> if (enabled) R.drawable.ic_battery_saver_rounded else R.drawable.ic_battery_full_rounded
            "night_light" -> R.drawable.ic_nightlight_fill_rounded
            "screen_record" -> R.drawable.ic_screen_record_rounded
            "airplane" -> if (enabled) R.drawable.ic_airplanemode_active_rounded else R.drawable.ic_airplanemode_inactive_rounded
            "screen_timeout" -> R.drawable.ic_screen_timeout_rounded
            "screenshot" -> R.drawable.ic_screenshot_rounded
            else -> R.drawable.ic_wifi_rounded
        }
        return ControlCard(
            id = id,
            title = com.example.consoleswitcher3.prefs.OverlayPrefs.controlItemTitle(id),
            cardIconRes = iconRes,
            isEnabled = if (id == "screenshot") true else enabled
        )
    }

    private fun isWifiEnabled(): Boolean {
        val pending = pendingToggleStates["wifi"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawWifiState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("wifi")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("wifi")
            }
        }
        return queryRawWifiState()
    }

    private fun queryRawWifiState(): Boolean {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                when (wm.wifiState) {
                    WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_ENABLED -> true
                    WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED -> false
                    else -> wm.isWifiEnabled
                }
            } else {
                Settings.Global.getInt(contentResolver, Settings.Global.WIFI_ON, 0) != 0
            }
        } catch (e: Exception) {
            try {
                Settings.Global.getInt(contentResolver, Settings.Global.WIFI_ON, 0) != 0
            } catch (ignored: Exception) {
                false
            }
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val pending = pendingToggleStates["bluetooth"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawBluetoothState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("bluetooth")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("bluetooth")
            }
        }
        return queryRawBluetoothState()
    }

    private fun queryRawBluetoothState(): Boolean {
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            if (adapter != null) {
                val state = try { adapter.state } catch (e: Exception) { -1 }
                when (state) {
                    BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_ON -> true
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> false
                    else -> adapter.isEnabled
                }
            } else {
                Settings.Global.getInt(contentResolver, Settings.Global.BLUETOOTH_ON, 0) != 0
            }
        } catch (e: Exception) {
            try {
                Settings.Global.getInt(contentResolver, Settings.Global.BLUETOOTH_ON, 0) != 0
            } catch (ignored: Exception) {
                false
            }
        }
    }

    private fun isDndEnabled(): Boolean {
        val pending = pendingToggleStates["dnd"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawDndState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("dnd")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("dnd")
            }
        }
        return queryRawDndState()
    }

    private fun queryRawDndState(): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (e: Exception) {
            try {
                Settings.Global.getInt(contentResolver, "zen_mode", 0) != 0
            } catch (ignored: Exception) {
                false
            }
        }
    }

    private fun isDarkModeEnabled(): Boolean {
        val pending = pendingToggleStates["dark_mode"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawDarkModeState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("dark_mode")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("dark_mode")
            }
        }
        return queryRawDarkModeState()
    }

    private fun queryRawDarkModeState(): Boolean {
        return try {
            val secureNight = Settings.Secure.getInt(contentResolver, "ui_night_mode", -1)
            if (secureNight == 2) return true
            if (secureNight == 1) return false
            val uim = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uim != null) {
                uim.nightMode == UiModeManager.MODE_NIGHT_YES
            } else {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        } catch (e: Exception) {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun isRotationAutoEnabled(): Boolean {
        val pending = pendingToggleStates["rotation"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawRotationAutoState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("rotation")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("rotation")
            }
        }
        return queryRawRotationAutoState()
    }

    private fun queryRawRotationAutoState(): Boolean {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun isBatterySaverEnabled(): Boolean {
        val pending = pendingToggleStates["battery_saver"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawBatterySaverState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("battery_saver")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("battery_saver")
            }
        }
        return queryRawBatterySaverState()
    }

    private fun queryRawBatterySaverState(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode ?: (Settings.Global.getInt(contentResolver, "low_power", 0) != 0)
        } catch (e: Exception) {
            try {
                Settings.Global.getInt(contentResolver, "low_power", 0) != 0
            } catch (ignored: Exception) {
                false
            }
        }
    }

    private fun isNightLightEnabled(): Boolean {
        val pending = pendingToggleStates["night_light"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawNightLightState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("night_light")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("night_light")
            }
        }
        return queryRawNightLightState()
    }

    private fun queryRawNightLightState(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, "night_display_activated", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun isAirplaneModeEnabled(): Boolean {
        val pending = pendingToggleStates["airplane"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawAirplaneModeState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("airplane")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("airplane")
            }
        }
        return queryRawAirplaneModeState()
    }

    private fun queryRawAirplaneModeState(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isScreenTimeoutKeepAwake(): Boolean {
        val pending = pendingToggleStates["screen_timeout"]
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.timestamp
            if (elapsed < PENDING_TOGGLE_TIMEOUT_MS) {
                val raw = queryRawScreenTimeoutState()
                if (raw == pending.targetState) {
                    pendingToggleStates.remove("screen_timeout")
                    return raw
                }
                return pending.targetState
            } else {
                pendingToggleStates.remove("screen_timeout")
            }
        }
        return queryRawScreenTimeoutState()
    }

    private fun queryRawScreenTimeoutState(): Boolean {
        return try {
            val timeout = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
            timeout >= 1800000
        } catch (e: Exception) {
            false
        }
    }

    private fun toggleSelectedControlCard() {
        if (isTransitioningMode || controlCards.isEmpty()) return
        val pos = findControlCenterPosition()
            ?.takeIf { it in 0 until controlAdapter.itemCount }
            ?: (controlPendingTarget ?: controlAdapter.selectedPosition)
                .takeIf { it in 0 until controlAdapter.itemCount }
            ?: return
        toggleControlCardAt(pos)
    }

    private fun openSelectedControlMenu() {
        if (isTransitioningMode || controlCards.isEmpty()) return
        val pos = findControlCenterPosition()
            ?.takeIf { it in 0 until controlAdapter.itemCount }
            ?: (controlPendingTarget ?: controlAdapter.selectedPosition)
                .takeIf { it in 0 until controlAdapter.itemCount }
            ?: return
        openControlMenuAt(pos)
    }

    private fun openControlMenuAt(position: Int) {
        if (position !in controlCards.indices) return
        val currentCard = controlCards[position]

        when (currentCard.id) {
            "wifi" -> {
                triggerHaptic()
                animateCardPress(position)
                openWifiPanel()
            }
            "bluetooth" -> {
                triggerHaptic()
                animateCardPress(position)
                openBluetoothPanel()
            }
            "screenshot" -> {
                triggerHaptic()
                animateCardPress(position)
                takeScreenshotAction()
            }
            "screen_record" -> {
                triggerHaptic()
                animateCardPress(position)
                toggleScreenRecordAction()
            }
            else -> {
                if (HandheldController.isMultiModeControl(currentCard.id)) {
                    triggerHaptic()
                    animateCardPress(position)
                    openHandheldModeModal(currentCard.id)
                } else {
                    // If it's just a toggle and not a menu, have the same function as X
                    toggleControlCardAt(position)
                }
            }
        }
    }

    private fun toggleControlCardAt(position: Int) {
        if (position !in controlCards.indices) return
        val currentCard = controlCards[position]
        if (currentCard.id == "screenshot") {
            triggerHaptic()
            animateCardPress(position)
            takeScreenshotAction()
            return
        }
        if (currentCard.id == "screen_record") {
            triggerHaptic()
            animateCardPress(position)
            toggleScreenRecordAction()
            return
        }
        if (HandheldController.isHandheldControl(currentCard.id)) {
            val currentValue = HandheldController.readCurrentValue(this, currentCard.id)
            val targetValue = HandheldController.nextCycleValue(currentCard.id, currentValue)
            triggerHaptic()
            animateCardPress(position)
            applyHandheldControlValueAt(position, currentCard.id, targetValue)
            return
        }
        val targetState = !currentCard.isEnabled
        triggerHaptic()
        animateCardPress(position)

        // Set pending toggle lock to prevent intermediate hardware states from reverting the UI
        pendingToggleStates[currentCard.id] = PendingToggle(targetState, System.currentTimeMillis())

        // Optimistically update UI immediately
        val optimisticCard = buildControlCardForState(currentCard.id, targetState)
        controlCards[position] = optimisticCard
        controlAdapter.updateCardAt(position, optimisticCard)

        lifecycleScope.launch {
            val handledInBackground = withContext(Dispatchers.IO) {
                applyControlToggle(currentCard.id, targetState)
            }
            if (!handledInBackground) {
                applyControlFallbackOnMain(currentCard.id, targetState)
            }
            // Verify state at progressive intervals until hardware confirms
            val checkDelays = listOf(500L, 1000L, 2000L, 3500L)
            for (checkDelay in checkDelays) {
                delay(checkDelay)
                if (isFinishing || isDestroyed) break
                refreshControlCards()
                if (!pendingToggleStates.containsKey(currentCard.id)) {
                    break
                }
            }
        }
    }

    private fun animateCardPress(position: Int) {
        val view = controlLayoutManager.findViewByPosition(position)
        val cardContainer = view?.findViewById<View>(R.id.cardContainer)
        cardContainer?.animate()?.cancel()
        cardContainer?.animate()
            ?.scaleX(0.95f)
            ?.scaleY(0.95f)
            ?.setDuration(70L)
            ?.withEndAction {
                cardContainer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(110L)
                    .start()
            }
            ?.start()
    }

    private fun openWifiPanel() {
        openWifiMenu()
    }

    private fun openBluetoothPanel() {
        openBluetoothMenu()
    }

    private fun setupBluetoothMenu() {
        bluetoothLayoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.rvBluetoothDevices.layoutManager = bluetoothLayoutManager
        binding.rvBluetoothDevices.itemAnimator = null

        bluetoothDeviceAdapter = BluetoothDeviceAdapter(
            items = emptyList(),
            onItemClick = { _, item ->
                triggerHaptic()
                if (isHandheldModalOpen) {
                    handleHandheldModalItemAction(item)
                } else if (isWifiMenuOpen) {
                    handleWifiNetworkItemAction(item)
                } else {
                    handleBluetoothDeviceItemAction(item)
                }
            }
        )
        binding.rvBluetoothDevices.adapter = bluetoothDeviceAdapter

        binding.bluetoothMenuOverlay.setOnClickListener {
            closeControlModal()
        }
        binding.cardBluetoothModal.setOnClickListener {
            // Consume clicks inside the modal card so it doesn't close
        }
    }

    private fun initBluetoothProfiles() {
        activeOverlayRef = WeakReference(this)
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: return
            val profilesToBind = mutableListOf(
                BluetoothProfile.HEADSET,
                BluetoothProfile.A2DP,
                PROFILE_HID_HOST
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                profilesToBind.add(21) // BluetoothProfile.HEARING_AID
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                profilesToBind.add(22) // BluetoothProfile.LE_AUDIO
            }
            for (p in profilesToBind) {
                if (!sharedBluetoothProfiles.containsKey(p) && requestedBluetoothProfiles.add(p)) {
                    try {
                        val started = adapter.getProfileProxy(applicationContext, sharedProfileListener, p)
                        if (!started) {
                            requestedBluetoothProfiles.remove(p)
                        }
                    } catch (ignored: Throwable) {
                        requestedBluetoothProfiles.remove(p)
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

    private fun closeBluetoothProfiles() {
        if (activeOverlayRef?.get() === this) {
            activeOverlayRef = null
        }
    }

    private fun hasBluetoothRuntimePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val hasConnect = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (hasConnect) return

        if (ShizukuManager.hasShizukuPermission()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ShizukuManager.execCommand("pm", "grant", packageName, Manifest.permission.BLUETOOTH_CONNECT)
                } catch (ignored: Throwable) {}
                withContext(Dispatchers.Main) {
                    initBluetoothProfiles()
                    if (isBluetoothMenuOpen) {
                        refreshBluetoothMenuUi()
                    }
                }
            }
        } else {
            try {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_CODE_BLUETOOTH_PERMS
                )
            } catch (ignored: Throwable) {}
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_BLUETOOTH_PERMS) {
            initBluetoothProfiles()
            if (isBluetoothMenuOpen) {
                refreshBluetoothMenuUi()
            }
        }
    }

    private fun applyBluetoothModalStyling() {
        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val density = resources.displayMetrics.density
        val cardBg = prefs.getCardBg(this)
        val cardBorder = prefs.getCardBorder(this)

        // Menus have a hardcoded 8dp corner radius
        val radiusPx = 16f * density
        val strokePx = (prefs.getBorderWidthDp(this).coerceAtLeast(1.5f) * density).toInt()

        binding.cardBluetoothModal.setCardBackgroundColor(Color.TRANSPARENT)
        binding.cardBluetoothModal.radius = radiusPx

        // Solution A: Combine fill & stroke in a single GradientDrawable so corners never bleed
        val combinedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(cardBg)
            setStroke(strokePx, cardBorder)
        }
        binding.bluetoothModalInner.background = combinedDrawable
        binding.vBluetoothModalBorder.background = null
        binding.tvBluetoothEmpty.setTextColor(cardBorder)
    }

    private fun openBluetoothMenu() {
        if (isBluetoothMenuOpen) return
        activeControlModal = ActiveControlModal.BLUETOOTH
        errorBluetoothAddresses.clear()

        applyBluetoothModalStyling()
        ensureBluetoothPermissions()
        initBluetoothProfiles()
        bluetoothDeviceAdapter.selectedPosition = 0
        refreshBluetoothMenuUi()
        animateOpenControlModal()
    }

    private fun openWifiMenu() {
        if (isWifiMenuOpen) return
        activeControlModal = ActiveControlModal.WIFI
        errorWifiNetworks.clear()

        applyBluetoothModalStyling()
        ensureWifiPermissions()
        bluetoothDeviceAdapter.selectedPosition = 0
        refreshWifiMenuUi()
        animateOpenControlModal()
    }

    private fun openHandheldModeModal(controlId: String) {
        if (isHandheldModalOpen && activeHandheldModalControlId == controlId) return
        activeControlModal = ActiveControlModal.HANDHELD_MODE
        activeHandheldModalControlId = controlId

        applyBluetoothModalStyling()
        val options = HandheldController.getModeOptions(controlId)
        val currentValue = HandheldController.readCurrentValue(this, controlId)
        val currentIdx = options.indexOfFirst { it.value == currentValue }.takeIf { it >= 0 } ?: 0
        bluetoothDeviceAdapter.selectedPosition = currentIdx
        refreshHandheldModalUi()
        animateOpenControlModal()
    }

    private fun animateOpenControlModal() {
        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        val isOff = prefs.isCloseAnimOff(closeSpeed)

        binding.bluetoothMenuOverlay.animate().cancel()
        binding.cardBluetoothModal.animate().cancel()
        binding.rvControlCenter.animate().cancel()

        binding.bluetoothMenuOverlay.visibility = View.VISIBLE
        if (isOff) {
            binding.bluetoothMenuOverlay.alpha = 1f
            binding.cardBluetoothModal.scaleX = 1f
            binding.cardBluetoothModal.scaleY = 1f
            binding.rvControlCenter.alpha = 0.25f
            return
        }

        val speedMult = (closeSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_CLOSE_SPEED).coerceIn(0.5f, 3.0f)
        val duration = (180L / speedMult).toLong().coerceIn(60L, 400L)

        binding.bluetoothMenuOverlay.alpha = 0f
        binding.cardBluetoothModal.scaleX = 0.92f
        binding.cardBluetoothModal.scaleY = 0.92f

        binding.bluetoothMenuOverlay.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        binding.cardBluetoothModal.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        binding.rvControlCenter.animate()
            .alpha(0.25f)
            .setDuration(duration)
            .start()
    }

    private fun closeBluetoothMenu() {
        closeControlModal()
    }

    private fun closeControlModal() {
        if (!isControlModalOpen) return
        activeControlModal = ActiveControlModal.NONE
        activeHandheldModalControlId = null
        errorBluetoothAddresses.clear()
        errorWifiNetworks.clear()
        triggerHaptic()

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        val isOff = prefs.isCloseAnimOff(closeSpeed)

        binding.bluetoothMenuOverlay.animate().cancel()
        binding.cardBluetoothModal.animate().cancel()
        binding.rvControlCenter.animate().cancel()

        if (isOff) {
            binding.bluetoothMenuOverlay.visibility = View.GONE
            binding.bluetoothMenuOverlay.alpha = 1f
            binding.rvControlCenter.alpha = 1f
            refreshControlCards()
            return
        }

        val speedMult = (closeSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_CLOSE_SPEED).coerceIn(0.5f, 3.0f)
        val duration = (150L / speedMult).toLong().coerceIn(50L, 350L)

        binding.bluetoothMenuOverlay.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                if (!isControlModalOpen) {
                    binding.bluetoothMenuOverlay.visibility = View.GONE
                    binding.bluetoothMenuOverlay.alpha = 1f
                }
            }
            .start()

        binding.cardBluetoothModal.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .start()

        binding.rvControlCenter.animate()
            .alpha(1f)
            .setDuration(duration)
            .start()

        refreshControlCards()
    }

    private fun toggleBluetoothFromModal() {
        val btIdx = controlCards.indexOfFirst { it.id == "bluetooth" }
        if (btIdx >= 0) {
            toggleControlCardAt(btIdx)
            binding.rvBluetoothDevices.postDelayed({
                if (!isFinishing && !isDestroyed && isBluetoothMenuOpen) {
                    refreshBluetoothMenuUi()
                }
            }, 150L)
            refreshBluetoothMenuUi()
        }
    }

    private fun toggleWifiFromModal() {
        val wifiIdx = controlCards.indexOfFirst { it.id == "wifi" }
        if (wifiIdx >= 0) {
            toggleControlCardAt(wifiIdx)
            binding.rvBluetoothDevices.postDelayed({
                if (!isFinishing && !isDestroyed && isWifiMenuOpen) {
                    refreshWifiMenuUi()
                }
            }, 150L)
            refreshWifiMenuUi()
        }
    }

    private fun refreshHandheldModalUi() {
        val controlId = activeHandheldModalControlId ?: return
        val currentValue = HandheldController.readCurrentValue(this, controlId)
        val items = HandheldController.getModeOptions(controlId).map { option ->
            BluetoothDeviceItem(
                address = "handheld:$controlId:${option.value}",
                name = option.label,
                isConnected = option.value == currentValue,
                isBusy = false,
                hasError = false,
                iconRes = getHandheldModeIconRes(controlId, option.value),
                device = null,
                networkId = option.value,
                isSettingsAction = false
            )
        }
        bluetoothDeviceAdapter.updateDevices(items)
        if (items.isNotEmpty()) {
            bluetoothDeviceAdapter.selectedPosition =
                bluetoothDeviceAdapter.selectedPosition.coerceIn(0, items.lastIndex)
        }
        binding.tvBluetoothEmpty.visibility = View.GONE
        binding.rvBluetoothDevices.visibility = View.VISIBLE
    }

    private fun handleSelectedHandheldModalAction() {
        val selected = bluetoothDeviceAdapter.getSelectedDevice() ?: return
        handleHandheldModalItemAction(selected)
    }

    private fun handleHandheldModalItemAction(item: BluetoothDeviceItem) {
        val controlId = activeHandheldModalControlId ?: return
        val targetValue = item.networkId
        val pos = controlCards.indexOfFirst { it.id == controlId }
        applyHandheldControlValueAt(pos, controlId, targetValue)
        closeControlModal()
    }

    private fun cycleHandheldModalOption() {
        val controlId = activeHandheldModalControlId ?: return
        val currentValue = HandheldController.readCurrentValue(this, controlId)
        val targetValue = HandheldController.nextCycleValue(controlId, currentValue)
        triggerHaptic()
        val options = HandheldController.getModeOptions(controlId)
        val targetIdx = options.indexOfFirst { it.value == targetValue }
        if (targetIdx >= 0) {
            bluetoothDeviceAdapter.selectedPosition = targetIdx
            binding.rvBluetoothDevices.scrollToPosition(targetIdx)
        }
        val pos = controlCards.indexOfFirst { it.id == controlId }
        applyHandheldControlValueAt(pos, controlId, targetValue)
        refreshHandheldModalUi()
    }

    private fun applyHandheldControlValueAt(position: Int, id: String, targetValue: Int) {
        HandheldController.setPendingValue(id, targetValue)
        if (position in controlCards.indices) {
            val optimisticCard = buildHandheldControlCard(id, targetValue)
            controlCards[position] = optimisticCard
            controlAdapter.updateCardAt(position, optimisticCard)
        }
        lifecycleScope.launch {
            val handled = withContext(Dispatchers.IO) {
                HandheldController.applyValue(this@OverlayActivity, id, targetValue)
            }
            if (!handled) {
                applyControlFallbackOnMain(id, targetValue != 0)
            }
            val checkDelays = listOf(300L, 800L, 1500L)
            for (checkDelay in checkDelays) {
                delay(checkDelay)
                if (isFinishing || isDestroyed) break
                refreshControlCards()
                if (isHandheldModalOpen && activeHandheldModalControlId == id) {
                    refreshHandheldModalUi()
                }
            }
        }
    }

    private fun isDeviceProfileConnected(device: BluetoothDevice): Boolean {
        for (proxy in bluetoothProfiles.values) {
            try {
                @Suppress("MissingPermission")
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        return false
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        // If any high-level profile is still actively in STATE_CONNECTING, do not report connected yet
        if (isDeviceConnecting(device)) return false

        // High-level profile connection is the authoritative signal for controllers/headsets
        if (isDeviceProfileConnected(device)) return true

        val addr = device.address
        if (addr != null && busyBluetoothAddresses[addr] == "Connecting...") {
            val startedAt = busyBluetoothTimestamps[addr] ?: 0L
            // Low-level radio ACL link comes up ~200-400ms after initiating connection, before
            // high-level profiles finish handshaking. Wait up to 2.5s for a profile to report
            // STATE_CONNECTED before accepting an ACL-only connection (for BLE/non-profile devices).
            if (bluetoothProfiles.isNotEmpty() && SystemClock.uptimeMillis() - startedAt < 2500L) {
                return false
            }
        }

        return isDeviceAclConnected(device)
    }

    private fun isDeviceConnecting(device: BluetoothDevice): Boolean {
        for (proxy in bluetoothProfiles.values) {
            try {
                @Suppress("MissingPermission")
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTING) {
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        return false
    }

    private fun isDeviceDisconnecting(device: BluetoothDevice): Boolean {
        for (proxy in bluetoothProfiles.values) {
            try {
                @Suppress("MissingPermission")
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTING) {
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        return false
    }

    private fun isDeviceAclConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun getBluetoothDeviceIcon(device: BluetoothDevice): Int {
        return try {
            @Suppress("MissingPermission")
            val btClass = device.bluetoothClass
            val major = btClass?.majorDeviceClass
            val name = (device.name ?: "").lowercase()
            when {
                major == BluetoothClass.Device.Major.PERIPHERAL ||
                    name.contains("controller") ||
                    name.contains("gamepad") ||
                    name.contains("xbox") ||
                    name.contains("dualsense") ||
                    name.contains("dualshock") ||
                    name.contains("8bitdo") ||
                    name.contains("joy-con") ||
                    name.contains("pro controller") ||
                    name.contains("gamesir") -> R.drawable.ic_gamepad_rounded
                major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    name.contains("airpods") ||
                    name.contains("buds") ||
                    name.contains("headphone") ||
                    name.contains("headset") ||
                    name.contains("speaker") -> R.drawable.ic_headphones_rounded
                else -> R.drawable.ic_bluetooth_rounded
            }
        } catch (ignored: Throwable) {
            R.drawable.ic_bluetooth_rounded
        }
    }

    private fun queryBluetoothDeviceItems(): List<BluetoothDeviceItem> {
        if (!isBluetoothEnabled() || !hasBluetoothRuntimePermission()) return emptyList()
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return emptyList()

        val items = mutableListOf<BluetoothDeviceItem>()

        try {
            @Suppress("MissingPermission")
            val bonded = adapter.bondedDevices ?: emptySet()
            for (device in bonded) {
                val addr = device.address ?: continue
                @Suppress("MissingPermission")
                val name = device.name?.takeIf { it.isNotBlank() } ?: addr
                val connected = isDeviceConnected(device)
                val busyText = busyBluetoothAddresses[addr]
                val isDisconnecting = busyText == "Disconnecting..." || isDeviceDisconnecting(device)
                val isConnecting = (busyText != null && !isDisconnecting) || (!connected && isDeviceConnecting(device))
                val isBusy = if (connected) isDisconnecting else isConnecting
                val isCleanConnected = connected && !isDisconnecting
                if (isCleanConnected) {
                    errorBluetoothAddresses.remove(addr)
                }
                val hasError = !isCleanConnected && !isBusy && errorBluetoothAddresses.contains(addr)

                items.add(
                    BluetoothDeviceItem(
                        address = addr,
                        name = name,
                        isConnected = isCleanConnected,
                        isBusy = isBusy,
                        hasError = hasError,
                        iconRes = getBluetoothDeviceIcon(device),
                        device = device,
                        isSettingsAction = false
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying bonded devices: ${e.message}")
        }

        // Sort paired devices: Connected first, then alphabetically
        items.sortWith(
            compareByDescending<BluetoothDeviceItem> { it.isConnected }
                .thenBy { it.name.lowercase() }
        )

        // Append button to pair new device (opens system Bluetooth Settings) at the end of the list
        items.add(
            BluetoothDeviceItem(
                address = ACTION_BLUETOOTH_SETTINGS,
                name = "Pair new device",
                isConnected = false,
                isBusy = false,
                iconRes = R.drawable.ic_add_rounded,
                device = null,
                isSettingsAction = true
            )
        )

        return items
    }

    private fun refreshBluetoothMenuUi() {
        val enabled = isBluetoothEnabled()

        if (!enabled) {
            bluetoothDeviceAdapter.updateDevices(emptyList())
            binding.rvBluetoothDevices.visibility = View.GONE
            binding.tvBluetoothEmpty.visibility = View.VISIBLE
            binding.tvBluetoothEmpty.text = "Bluetooth is turned off\nPress (X) to turn on"
            return
        }

        if (!hasBluetoothRuntimePermission()) {
            bluetoothDeviceAdapter.updateDevices(emptyList())
            binding.rvBluetoothDevices.visibility = View.GONE
            binding.tvBluetoothEmpty.visibility = View.VISIBLE
            binding.tvBluetoothEmpty.text = "Bluetooth permission required\nPress (A) to grant"
            return
        }

        val items = queryBluetoothDeviceItems()
        bluetoothDeviceAdapter.updateDevices(items)

        binding.tvBluetoothEmpty.visibility = View.GONE
        binding.rvBluetoothDevices.visibility = View.VISIBLE
    }

    private fun moveBluetoothSelection(delta: Int) {
        val count = bluetoothDeviceAdapter.itemCount
        if (count <= 0) return
        val newPos = (bluetoothDeviceAdapter.selectedPosition + delta).coerceIn(0, count - 1)
        if (newPos != bluetoothDeviceAdapter.selectedPosition) {
            triggerHaptic()
            bluetoothDeviceAdapter.selectedPosition = newPos
            binding.rvBluetoothDevices.scrollToPosition(newPos)
        }
    }

    private fun handleSelectedBluetoothDeviceAction() {
        if (!isBluetoothEnabled()) {
            toggleBluetoothFromModal()
            return
        }
        if (!hasBluetoothRuntimePermission()) {
            triggerHaptic()
            ensureBluetoothPermissions()
            return
        }
        val selected = bluetoothDeviceAdapter.getSelectedDevice() ?: return
        triggerHaptic()
        handleBluetoothDeviceItemAction(selected)
    }

    private fun handleBluetoothDeviceItemAction(item: BluetoothDeviceItem) {
        if (item.isSettingsAction) {
            openSystemBluetoothSettings()
            return
        }
        val device = item.device ?: return
        val targetConnect = !item.isConnected
        triggerBluetoothDeviceConnection(device, targetConnect)
    }

    private fun openSystemBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Bluetooth settings: ${e.message}")
        }
        dismissSwitcher()
    }

    private fun triggerBluetoothDeviceConnection(device: BluetoothDevice, connect: Boolean) {
        val addr = device.address ?: return
        errorBluetoothAddresses.remove(addr)
        busyBluetoothAddresses[addr] = if (connect) "Connecting..." else "Disconnecting..."
        busyBluetoothTimestamps[addr] = SystemClock.uptimeMillis()
        refreshBluetoothMenuUi()

        lifecycleScope.launch {
            val initiated = withContext(Dispatchers.IO) {
                connectOrDisconnectBluetoothDevice(device, connect)
            }

            val startTime = SystemClock.uptimeMillis()
            val maxWaitMs = 15000L
            while (SystemClock.uptimeMillis() - startTime < maxWaitMs) {
                delay(400L)
                if (isFinishing || isDestroyed) break
                if (!busyBluetoothAddresses.containsKey(addr)) return@launch

                val nowConnected = isDeviceConnected(device)
                val nowConnecting = isDeviceConnecting(device)

                if (connect) {
                    if (nowConnected) {
                        busyBluetoothAddresses.remove(addr)
                        busyBluetoothTimestamps.remove(addr)
                        errorBluetoothAddresses.remove(addr)
                        if (isBluetoothMenuOpen) refreshBluetoothMenuUi()
                        return@launch
                    }
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    // If after 5s (or 2s when invocation returned false) neither profiles nor ACL link is active, connection attempt failed
                    val failThreshold = if (initiated) 5000L else 2000L
                    if (elapsed > failThreshold && !nowConnecting && !nowConnected && !isDeviceAclConnected(device)) {
                        break
                    }
                } else {
                    if (!nowConnected) {
                        busyBluetoothAddresses.remove(addr)
                        busyBluetoothTimestamps.remove(addr)
                        if (isBluetoothMenuOpen) refreshBluetoothMenuUi()
                        return@launch
                    }
                }
            }

            if (connect && busyBluetoothAddresses[addr] == "Connecting..." && isBluetoothEnabled() && !isDeviceConnected(device)) {
                errorBluetoothAddresses.add(addr)
            }
            busyBluetoothAddresses.remove(addr)
            busyBluetoothTimestamps.remove(addr)
            if (!isFinishing && !isDestroyed && isBluetoothMenuOpen) {
                refreshBluetoothMenuUi()
            }
        }
    }

    private fun connectOrDisconnectBluetoothDevice(device: BluetoothDevice, connect: Boolean): Boolean {
        val methodName = if (connect) "connect" else "disconnect"
        var anySucceeded = false

        // 1. Try direct method on BluetoothDevice (available on Android 10-12 & many OEM builds)
        try {
            val m = BluetoothDevice::class.java.getMethod(methodName)
            m.isAccessible = true
            val res = m.invoke(device) as? Boolean
            if (res == true) anySucceeded = true
        } catch (ignored: Throwable) {}

        // 2. Try via active BluetoothProfile proxies (HID_HOST for controllers, A2DP/HEADSET for audio)
        for ((_, profile) in bluetoothProfiles) {
            if (!connect) {
                val isCurrentlyConnectedOnProfile = try {
                    @Suppress("MissingPermission")
                    profile.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                } catch (ignored: Throwable) { true }
                if (!isCurrentlyConnectedOnProfile) continue
            }
            if (invokeProfileConnectOrDisconnect(profile, device, methodName)) {
                anySucceeded = true
            }
        }
        return anySucceeded
    }

    private fun invokeProfileConnectOrDisconnect(
        profile: BluetoothProfile,
        device: BluetoothDevice,
        methodName: String
    ): Boolean {
        // Attempt A: Direct reflection on profile proxy
        try {
            val method = profile.javaClass.getMethod(methodName, BluetoothDevice::class.java)
            method.isAccessible = true
            val ok = method.invoke(profile, device) as? Boolean
            if (ok == true) return true
        } catch (ignored: Throwable) {}

        // Attempt B: Privileged call via ShizukuBinderWrapper (executes as UID 2000 shell with BLUETOOTH_PRIVILEGED)
        if (!ShizukuManager.hasShizukuPermission()) return false
        return try {
            invokeProfileWithShizuku(profile, device, methodName)
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku profile $methodName failed: ${e.message}")
            false
        }
    }

    private fun invokeProfileWithShizuku(
        profile: BluetoothProfile,
        device: BluetoothDevice,
        methodName: String
    ): Boolean {
        val profileClass = profile.javaClass

        // Locate the underlying AIDL IInterface service (either on profile.mService or profile.mProfileConnector.mService)
        var serviceHolder: Any = profile
        var serviceField = try {
            profileClass.getDeclaredField("mService").apply { isAccessible = true }
        } catch (ignored: Throwable) { null }

        if (serviceField == null) {
            val connectorField = try {
                profileClass.getDeclaredField("mProfileConnector").apply { isAccessible = true }
            } catch (ignored: Throwable) { null }
            val connector = connectorField?.get(profile)
            if (connector != null) {
                serviceHolder = connector
                serviceField = try {
                    connector.javaClass.getDeclaredField("mService").apply { isAccessible = true }
                } catch (ignored: Throwable) { null }
            }
        }

        val originalService = serviceField?.get(serviceHolder) as? IInterface ?: return false
        val rawBinder = originalService.asBinder() ?: return false
        val wrappedBinder = if (rawBinder is ShizukuBinderWrapper) rawBinder else ShizukuBinderWrapper(rawBinder)
        val stubClass = originalService.javaClass.enclosingClass ?: return false
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val wrappedService = asInterface.invoke(null, wrappedBinder) ?: return false

        // Temporarily swap mAttributionSource to shell (UID 2000) on API 31+ so system_server accepts the privileged binder call
        val attrField = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                profileClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }
            } catch (ignored: Throwable) { null }
        } else null
        val originalAttr = attrField?.get(profile)

        try {
            serviceField.set(serviceHolder, wrappedService)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attrField != null) {
                val shellAttr = android.content.AttributionSource.Builder(ShizukuManager.getShizukuUid())
                    .setPackageName("com.android.shell")
                    .build()
                attrField.set(profile, shellAttr)
            }
            val method = profileClass.getMethod(methodName, BluetoothDevice::class.java)
            method.isAccessible = true
            return (method.invoke(profile, device) as? Boolean) == true
        } finally {
            try {
                serviceField.set(serviceHolder, originalService)
            } catch (ignored: Throwable) {}
            if (attrField != null && originalAttr != null) {
                try {
                    attrField.set(profile, originalAttr)
                } catch (ignored: Throwable) {}
            }
        }
    }

    private fun ensureWifiPermissions() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine) return

        if (ShizukuManager.hasShizukuPermission()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ShizukuManager.execCommand("pm", "grant", packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
                    ShizukuManager.execCommand("pm", "grant", packageName, Manifest.permission.ACCESS_FINE_LOCATION)
                } catch (ignored: Throwable) {}
                withContext(Dispatchers.Main) {
                    if (isWifiMenuOpen) {
                        refreshWifiMenuUi()
                    }
                }
            }
        }
    }

    private fun stripSsidQuotes(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.equals("<unknown ssid>", ignoreCase = true) || trimmed == "0x") return ""
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun getPrivilegedWifiService(): Any? {
        if (!ShizukuManager.hasShizukuPermission()) return null
        return try {
            val binder = ShizukuManager.getSystemServiceBinder(Context.WIFI_SERVICE) ?: return null
            val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun extractListFromSliceOrList(obj: Any?): List<Any> {
        if (obj == null) return emptyList()
        if (obj is List<*>) return obj.filterNotNull()
        return try {
            val getListMethod = obj.javaClass.getMethod("getList")
            val list = getListMethod.invoke(obj) as? List<*>
            list?.filterNotNull() ?: emptyList()
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun querySavedWifiConfigsPrivileged(): List<SavedWifiEntry> {
        val resultBySsid = linkedMapOf<String, SavedWifiEntry>()

        // 1. Try privileged IWifiManager via ShizukuBinderWrapper
        val service = getPrivilegedWifiService()
        if (service != null) {
            val methodNames = listOf("getPrivilegedConfiguredNetworks", "getConfiguredNetworks")
            for (methodName in methodNames) {
                if (resultBySsid.isNotEmpty()) break
                for (method in service.javaClass.methods) {
                    if (method.name != methodName) continue
                    try {
                        val pTypes = method.parameterTypes
                        val args = arrayOfNulls<Any>(pTypes.size)
                        var supported = true
                        for (i in pTypes.indices) {
                            val pt = pTypes[i]
                            args[i] = when {
                                pt == String::class.java -> "com.android.shell"
                                pt == Bundle::class.java -> Bundle()
                                pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.javaObjectType -> false
                                pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> ShizukuManager.getShizukuUid()
                                else -> {
                                    supported = false
                                    null
                                }
                            }
                        }
                        if (!supported) continue
                        val res = method.invoke(service, *args)
                        val configs = extractListFromSliceOrList(res)
                        for (cfg in configs) {
                            if (cfg is android.net.wifi.WifiConfiguration) {
                                val ssid = stripSsidQuotes(cfg.SSID)
                                if (ssid.isNotBlank() && cfg.networkId >= 0) {
                                    resultBySsid[ssid] = SavedWifiEntry(cfg.networkId, ssid)
                                }
                            }
                        }
                        if (resultBySsid.isNotEmpty()) break
                    } catch (ignored: Throwable) {}
                }
            }
        }

        // 2. Try standard WifiManager.configuredNetworks fallback
        if (resultBySsid.isEmpty()) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("MissingPermission")
                val configs = wm?.configuredNetworks ?: emptyList()
                for (cfg in configs) {
                    val ssid = stripSsidQuotes(cfg.SSID)
                    if (ssid.isNotBlank() && cfg.networkId >= 0) {
                        resultBySsid[ssid] = SavedWifiEntry(cfg.networkId, ssid)
                    }
                }
            } catch (ignored: Throwable) {}
        }

        // 3. Merge cached shell `cmd wifi list-networks` entries
        for (entry in cachedShellWifiNetworks) {
            if (!resultBySsid.containsKey(entry.ssid)) {
                resultBySsid[entry.ssid] = entry
            }
        }

        return resultBySsid.values.toList()
    }

    private data class ActiveWifiStatus(
        val networkId: Int,
        val ssid: String,
        val isConnected: Boolean,
        val isConnecting: Boolean
    )

    @Suppress("DEPRECATION")
    private fun queryActiveWifiStatus(): ActiveWifiStatus {
        var netId = -1
        var ssid = ""
        var supplicantStateName = ""

        // 1. Try privileged IWifiManager.getConnectionInfo via ShizukuBinderWrapper
        val service = getPrivilegedWifiService()
        if (service != null) {
            for (method in service.javaClass.methods) {
                if (method.name != "getConnectionInfo") continue
                try {
                    val pTypes = method.parameterTypes
                    val args = arrayOfNulls<Any>(pTypes.size)
                    var supported = true
                    for (i in pTypes.indices) {
                        val pt = pTypes[i]
                        args[i] = when {
                            pt == String::class.java -> "com.android.shell"
                            pt == Bundle::class.java -> Bundle()
                            pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.javaObjectType -> false
                            else -> {
                                supported = false
                                null
                            }
                        }
                    }
                    if (!supported) continue
                    val info = method.invoke(service, *args) as? android.net.wifi.WifiInfo
                    if (info != null) {
                        netId = info.networkId
                        ssid = stripSsidQuotes(info.ssid)
                        supplicantStateName = info.supplicantState?.name ?: ""
                        if (netId >= 0 || ssid.isNotBlank()) break
                    }
                } catch (ignored: Throwable) {}
            }
        }

        // 2. Fallback to standard WifiManager.connectionInfo
        if (netId < 0 && ssid.isBlank()) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                if (info != null) {
                    netId = info.networkId
                    ssid = stripSsidQuotes(info.ssid)
                    if (supplicantStateName.isBlank()) {
                        supplicantStateName = info.supplicantState?.name ?: ""
                    }
                }
            } catch (ignored: Throwable) {}
        }

        // 3. Fallback to cached shell status if needed
        if (ssid.isBlank() && !cachedShellConnectedSsid.isNullOrBlank()) {
            ssid = cachedShellConnectedSsid ?: ""
        }
        if (netId < 0 && cachedShellConnectedNetId >= 0) {
            netId = cachedShellConnectedNetId
        }

        val isSupplicantConnecting = supplicantStateName in setOf(
            "AUTHENTICATING",
            "ASSOCIATING",
            "ASSOCIATED",
            "FOUR_WAY_HANDSHAKE",
            "GROUP_HANDSHAKE"
        )
        val isSupplicantCompleted = supplicantStateName == "COMPLETED" ||
            (supplicantStateName.isBlank() && (netId >= 0 || ssid.isNotBlank()))

        val isConnected = (netId >= 0 || ssid.isNotBlank()) && isSupplicantCompleted && !isSupplicantConnecting
        val isConnecting = (netId >= 0 || ssid.isNotBlank()) && isSupplicantConnecting

        return ActiveWifiStatus(
            networkId = netId,
            ssid = ssid,
            isConnected = isConnected,
            isConnecting = isConnecting
        )
    }

    private fun refreshShellWifiCacheAsync() {
        if (isRefreshingShellWifi || !ShizukuManager.hasShizukuPermission()) return
        isRefreshingShellWifi = true
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val listEntries = mutableListOf<SavedWifiEntry>()
                var connectedSsid: String? = null
                var connectedNetId = -1
                try {
                    val listRes = ShizukuManager.execCommand("cmd", "wifi", "list-networks")
                    if (listRes.isSuccess && listRes.output.isNotBlank()) {
                        for (rawLine in listRes.output.lines()) {
                            val line = rawLine.trim()
                            if (line.isEmpty() || line.startsWith("Network Id", ignoreCase = true)) continue
                            val match = Regex("^(\\d+)\\s+(.+?)\\s{2,}.*$").find(line)
                                ?: Regex("^(\\d+)\\s+(\\S+)\\s+.*$").find(line)
                            if (match != null) {
                                val id = match.groupValues[1].toIntOrNull() ?: continue
                                val netSsid = stripSsidQuotes(match.groupValues[2].trim())
                                if (netSsid.isNotBlank()) {
                                    listEntries.add(SavedWifiEntry(id, netSsid))
                                }
                            }
                        }
                    }
                    val statusRes = ShizukuManager.execCommand("cmd", "wifi", "status")
                    if (statusRes.isSuccess && statusRes.output.isNotBlank()) {
                        val out = statusRes.output
                        if (out.contains("Wifi is connected to", ignoreCase = true)) {
                            val ssidMatch = Regex("Wifi is connected to\\s+\"([^\"]+)\"").find(out)
                            connectedSsid = ssidMatch?.groupValues?.getOrNull(1)
                        }
                        val netIdMatch = Regex("networkId:\\s*(\\d+)").find(out)
                            ?: Regex("Net ID:\\s*(\\d+)").find(out)
                        if (netIdMatch != null) {
                            connectedNetId = netIdMatch.groupValues[1].toIntOrNull() ?: -1
                        }
                    }
                } catch (ignored: Throwable) {}
                Triple(listEntries, connectedSsid, connectedNetId)
            }
            isRefreshingShellWifi = false
            cachedShellWifiNetworks = parsed.first
            cachedShellConnectedSsid = parsed.second
            cachedShellConnectedNetId = parsed.third
            if (!isFinishing && !isDestroyed && isWifiMenuOpen) {
                val items = queryWifiNetworkItems()
                bluetoothDeviceAdapter.updateDevices(items)
            }
        }
    }

    private fun queryWifiNetworkItems(): List<BluetoothDeviceItem> {
        if (!isWifiEnabled()) return emptyList()

        val savedConfigs = querySavedWifiConfigsPrivileged()
        val activeStatus = queryActiveWifiStatus()
        val items = mutableListOf<BluetoothDeviceItem>()
        val seenSsids = mutableSetOf<String>()

        for (entry in savedConfigs) {
            if (!seenSsids.add(entry.ssid)) continue
            val key = "wifi:${entry.ssid}"
            val matchesActive = (activeStatus.networkId >= 0 && activeStatus.networkId == entry.networkId) ||
                (activeStatus.ssid.isNotBlank() && activeStatus.ssid == entry.ssid)

            val busyText = busyWifiNetworks[key]
            val isDisconnecting = busyText == "Disconnecting..."
            val rawConnected = matchesActive && activeStatus.isConnected
            val isConnecting = (busyText != null && !isDisconnecting && !rawConnected) ||
                (matchesActive && activeStatus.isConnecting)
            val isBusy = if (rawConnected) isDisconnecting else isConnecting
            val isCleanConnected = rawConnected && !isDisconnecting

            if (isCleanConnected) {
                errorWifiNetworks.remove(key)
                if (busyText == "Connecting...") {
                    busyWifiNetworks.remove(key)
                }
            }
            val hasError = !isCleanConnected && !isBusy && errorWifiNetworks.contains(key)

            items.add(
                BluetoothDeviceItem(
                    address = key,
                    name = entry.ssid,
                    isConnected = isCleanConnected,
                    isBusy = isBusy,
                    hasError = hasError,
                    iconRes = R.drawable.ic_wifi_rounded,
                    device = null,
                    networkId = entry.networkId,
                    isSettingsAction = false
                )
            )
        }

        // If the currently connected network wasn't in the saved list yet, include it at the top
        if (activeStatus.ssid.isNotBlank() && !seenSsids.contains(activeStatus.ssid)) {
            val key = "wifi:${activeStatus.ssid}"
            val busyText = busyWifiNetworks[key]
            val isDisconnecting = busyText == "Disconnecting..."
            val isCleanConnected = activeStatus.isConnected && !isDisconnecting
            val isBusy = if (activeStatus.isConnected) isDisconnecting else (busyText != null || activeStatus.isConnecting)
            if (isCleanConnected) {
                errorWifiNetworks.remove(key)
            }
            val hasError = !isCleanConnected && !isBusy && errorWifiNetworks.contains(key)
            items.add(
                BluetoothDeviceItem(
                    address = key,
                    name = activeStatus.ssid,
                    isConnected = isCleanConnected,
                    isBusy = isBusy,
                    hasError = hasError,
                    iconRes = R.drawable.ic_wifi_rounded,
                    device = null,
                    networkId = activeStatus.networkId,
                    isSettingsAction = false
                )
            )
        }

        // Sort saved networks: Connected first, then alphabetically
        items.sortWith(
            compareByDescending<BluetoothDeviceItem> { it.isConnected }
                .thenBy { it.name.lowercase() }
        )

        // Append button to add new network (opens system Wi-Fi Settings) at the end of the list
        items.add(
            BluetoothDeviceItem(
                address = ACTION_WIFI_SETTINGS,
                name = "Add new network",
                isConnected = false,
                isBusy = false,
                iconRes = R.drawable.ic_add_rounded,
                device = null,
                networkId = -1,
                isSettingsAction = true
            )
        )

        return items
    }

    private fun refreshWifiMenuUi() {
        val enabled = isWifiEnabled()

        if (!enabled) {
            bluetoothDeviceAdapter.updateDevices(emptyList())
            binding.rvBluetoothDevices.visibility = View.GONE
            binding.tvBluetoothEmpty.visibility = View.VISIBLE
            binding.tvBluetoothEmpty.text = "Wi-Fi is turned off\nPress (X) to turn on"
            return
        }

        val items = queryWifiNetworkItems()
        bluetoothDeviceAdapter.updateDevices(items)

        binding.tvBluetoothEmpty.visibility = View.GONE
        binding.rvBluetoothDevices.visibility = View.VISIBLE

        refreshShellWifiCacheAsync()
    }

    private fun handleSelectedWifiNetworkAction() {
        if (!isWifiEnabled()) {
            toggleWifiFromModal()
            return
        }
        val selected = bluetoothDeviceAdapter.getSelectedDevice() ?: return
        triggerHaptic()
        handleWifiNetworkItemAction(selected)
    }

    private fun handleWifiNetworkItemAction(item: BluetoothDeviceItem) {
        if (item.isSettingsAction) {
            openSystemWifiSettings()
            return
        }
        val targetConnect = !item.isConnected
        triggerWifiNetworkConnection(item, targetConnect)
    }

    private fun openSystemWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Wi-Fi settings: ${e.message}")
        }
        dismissSwitcher()
    }

    private fun triggerWifiNetworkConnection(item: BluetoothDeviceItem, connect: Boolean) {
        val key = item.address
        val targetSsid = item.name
        val targetNetId = item.networkId

        errorWifiNetworks.remove(key)
        if (connect) {
            // Clear any previous "Connecting..." states on other networks when switching
            val iter = busyWifiNetworks.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key != key && entry.value == "Connecting...") {
                    iter.remove()
                }
            }
            busyWifiNetworks[key] = "Connecting..."
        } else {
            busyWifiNetworks[key] = "Disconnecting..."
        }
        refreshWifiMenuUi()

        lifecycleScope.launch {
            val initiated = withContext(Dispatchers.IO) {
                connectOrDisconnectWifiNetwork(targetNetId, targetSsid, connect)
            }

            val startTime = SystemClock.uptimeMillis()
            val maxWaitMs = 12000L
            while (SystemClock.uptimeMillis() - startTime < maxWaitMs) {
                delay(400L)
                if (isFinishing || isDestroyed) break
                if (!busyWifiNetworks.containsKey(key)) return@launch

                val status = queryActiveWifiStatus()
                val matchesTarget = (targetNetId >= 0 && status.networkId == targetNetId) ||
                    (targetSsid.isNotBlank() && status.ssid == targetSsid)

                if (connect) {
                    if (matchesTarget && status.isConnected) {
                        busyWifiNetworks.remove(key)
                        errorWifiNetworks.remove(key)
                        cachedShellConnectedSsid = targetSsid
                        cachedShellConnectedNetId = targetNetId
                        if (isWifiMenuOpen) refreshWifiMenuUi()
                        return@launch
                    }
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    // If connection invocation failed or after 5.5s supplicant is not connecting to target network, treat as failed
                    val failThreshold = if (initiated) 5500L else 2000L
                    if (elapsed > failThreshold && !matchesTarget && !status.isConnecting) {
                        break
                    }
                } else {
                    if (!matchesTarget || !status.isConnected) {
                        busyWifiNetworks.remove(key)
                        if (cachedShellConnectedSsid == targetSsid) {
                            cachedShellConnectedSsid = null
                            cachedShellConnectedNetId = -1
                        }
                        if (isWifiMenuOpen) refreshWifiMenuUi()
                        return@launch
                    }
                }
            }

            if (connect && busyWifiNetworks[key] == "Connecting..." && isWifiEnabled()) {
                val finalStatus = queryActiveWifiStatus()
                val finalMatches = (targetNetId >= 0 && finalStatus.networkId == targetNetId) ||
                    (targetSsid.isNotBlank() && finalStatus.ssid == targetSsid)
                if (!finalMatches || !finalStatus.isConnected) {
                    errorWifiNetworks.add(key)
                }
            }
            busyWifiNetworks.remove(key)
            if (!isFinishing && !isDestroyed && isWifiMenuOpen) {
                refreshWifiMenuUi()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun connectOrDisconnectWifiNetwork(networkId: Int, ssid: String, connect: Boolean): Boolean {
        var anySucceeded = false
        val service = getPrivilegedWifiService()

        if (connect) {
            // 1. Privileged IWifiManager.connect(...) and enableNetwork(netId, true, ...) via ShizukuBinderWrapper
            if (service != null && networkId >= 0) {
                for (method in service.javaClass.methods) {
                    if (method.name == "connect") {
                        try {
                            val pTypes = method.parameterTypes
                            if (pTypes.size >= 3 &&
                                pTypes[0] == android.net.wifi.WifiConfiguration::class.java &&
                                (pTypes[1] == Int::class.javaPrimitiveType || pTypes[1] == Int::class.javaObjectType) &&
                                pTypes[2] == IBinder::class.java
                            ) {
                                val args = arrayOfNulls<Any>(pTypes.size)
                                args[0] = null
                                args[1] = networkId
                                args[2] = android.os.Binder()
                                for (i in 3 until pTypes.size) {
                                    val pt = pTypes[i]
                                    args[i] = when {
                                        pt == String::class.java -> "com.android.shell"
                                        pt == Bundle::class.java -> Bundle()
                                        pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> 0
                                        else -> null
                                    }
                                }
                                method.invoke(service, *args)
                                anySucceeded = true
                                break
                            }
                        } catch (ignored: Throwable) {}
                    }
                }

                for (method in service.javaClass.methods) {
                    if (method.name == "enableNetwork") {
                        try {
                            val pTypes = method.parameterTypes
                            if (pTypes.size >= 2 &&
                                (pTypes[0] == Int::class.javaPrimitiveType || pTypes[0] == Int::class.javaObjectType) &&
                                (pTypes[1] == Boolean::class.javaPrimitiveType || pTypes[1] == Boolean::class.javaObjectType)
                            ) {
                                val args = arrayOfNulls<Any>(pTypes.size)
                                args[0] = networkId
                                args[1] = true
                                for (i in 2 until pTypes.size) {
                                    val pt = pTypes[i]
                                    args[i] = when {
                                        pt == String::class.java -> "com.android.shell"
                                        pt == Bundle::class.java -> Bundle()
                                        else -> null
                                    }
                                }
                                val ok = method.invoke(service, *args) as? Boolean
                                if (ok == true) anySucceeded = true
                                break
                            }
                        } catch (ignored: Throwable) {}
                    }
                }

                for (method in service.javaClass.methods) {
                    if (method.name == "reconnect") {
                        try {
                            val pTypes = method.parameterTypes
                            val args = arrayOfNulls<Any>(pTypes.size)
                            for (i in pTypes.indices) {
                                val pt = pTypes[i]
                                args[i] = when {
                                    pt == String::class.java -> "com.android.shell"
                                    pt == Bundle::class.java -> Bundle()
                                    else -> null
                                }
                            }
                            method.invoke(service, *args)
                            break
                        } catch (ignored: Throwable) {}
                    }
                }
            }

            // 2. Fallback to standard WifiManager.enableNetwork
            if (!anySucceeded && networkId >= 0) {
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wm != null) {
                        if (wm.enableNetwork(networkId, true)) {
                            wm.reconnect()
                            anySucceeded = true
                        }
                    }
                } catch (ignored: Throwable) {}
            }
        } else {
            // Disconnect from current network
            if (service != null) {
                for (method in service.javaClass.methods) {
                    if (method.name == "disconnect") {
                        try {
                            val pTypes = method.parameterTypes
                            val args = arrayOfNulls<Any>(pTypes.size)
                            for (i in pTypes.indices) {
                                val pt = pTypes[i]
                                args[i] = when {
                                    pt == String::class.java -> "com.android.shell"
                                    pt == Bundle::class.java -> Bundle()
                                    else -> null
                                }
                            }
                            val ok = method.invoke(service, *args) as? Boolean
                            if (ok == true) anySucceeded = true
                            break
                        } catch (ignored: Throwable) {}
                    }
                }
            }
            if (!anySucceeded) {
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wm?.disconnect() == true) {
                        anySucceeded = true
                    }
                } catch (ignored: Throwable) {}
            }
        }

        return anySucceeded
    }

    private suspend fun applyControlToggle(id: String, enable: Boolean): Boolean {
        val hasShizuku = ShizukuManager.hasShizukuPermission()
        return when (id) {
            "wifi" -> {
                if (hasShizuku) {
                    val cmdRes = ShizukuManager.execCommand("cmd", "wifi", "set-wifi-enabled", if (enable) "enabled" else "disabled")
                    if (cmdRes.isSuccess) return true
                    val svcRes = ShizukuManager.execCommand("svc", "wifi", if (enable) "enable" else "disable")
                    if (svcRes.isSuccess) return true
                }
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    if (wm != null && wm.setWifiEnabled(enable)) return true
                } catch (ignored: Exception) {}
                false
            }
            "bluetooth" -> {
                if (hasShizuku) {
                    val cmdRes = ShizukuManager.execCommand("cmd", "bluetooth_manager", if (enable) "enable" else "disable")
                    if (cmdRes.isSuccess) return true
                    val svcRes = ShizukuManager.execCommand("svc", "bluetooth", if (enable) "enable" else "disable")
                    if (svcRes.isSuccess) return true
                }
                try {
                    val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = bm?.adapter
                    @Suppress("DEPRECATION", "MissingPermission")
                    if (adapter != null) {
                        val ok = if (enable) adapter.enable() else adapter.disable()
                        if (ok) return true
                    }
                } catch (ignored: Exception) {}
                false
            }
            "dnd" -> {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    try {
                        nm.setInterruptionFilter(
                            if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            else NotificationManager.INTERRUPTION_FILTER_ALL
                        )
                        return true
                    } catch (ignored: Exception) {}
                }
                if (hasShizuku) {
                    val res = ShizukuManager.execCommand("cmd", "notification", "set_dnd", if (enable) "priority" else "off")
                    if (res.isSuccess) return true
                    val altRes = ShizukuManager.execCommand("cmd", "notification", "set_dnd", if (enable) "on" else "off")
                    if (altRes.isSuccess) return true
                }
                false
            }
            "dark_mode" -> {
                if (hasShizuku) {
                    val res = ShizukuManager.execCommand("cmd", "uimode", "night", if (enable) "yes" else "no")
                    if (res.isSuccess) return true
                    val secRes = ShizukuManager.execCommand("settings", "put", "secure", "ui_night_mode", if (enable) "2" else "1")
                    if (secRes.isSuccess) return true
                }
                false
            }
            "rotation" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
                    try {
                        Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
                        return true
                    } catch (ignored: Exception) {}
                }
                if (hasShizuku) {
                    val res = ShizukuManager.execCommand("settings", "put", "system", "accelerometer_rotation", if (enable) "1" else "0")
                    if (res.isSuccess) return true
                }
                false
            }
            "battery_saver" -> {
                if (hasShizuku) {
                    val cmdRes = ShizukuManager.execCommand("cmd", "power", "set-mode", if (enable) "1" else "0")
                    if (cmdRes.isSuccess) return true
                    val secRes = ShizukuManager.execCommand("settings", "put", "global", "low_power", if (enable) "1" else "0")
                    if (secRes.isSuccess) return true
                }
                false
            }
            "night_light" -> {
                if (hasShizuku) {
                    val cmdRes = ShizukuManager.execCommand("cmd", "color_display", "set-night-display-activated", if (enable) "1" else "0")
                    if (cmdRes.isSuccess) return true
                    val secRes = ShizukuManager.execCommand("settings", "put", "secure", "night_display_activated", if (enable) "1" else "0")
                    if (secRes.isSuccess) return true
                }
                false
            }
            "airplane" -> {
                if (hasShizuku) {
                    val cmdRes = ShizukuManager.execCommand("cmd", "connectivity", "airplane-mode", if (enable) "enable" else "disable")
                    if (cmdRes.isSuccess) return true
                    val setRes = ShizukuManager.execCommand("settings", "put", "global", "airplane_mode_on", if (enable) "1" else "0")
                    ShizukuManager.execCommand("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", if (enable) "true" else "false")
                    if (setRes.isSuccess) return true
                }
                false
            }
            "screen_timeout" -> {
                val targetTimeout = if (enable) 86400000 else 60000
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
                    try {
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, targetTimeout)
                        return true
                    } catch (ignored: Exception) {}
                }
                if (hasShizuku) {
                    val res = ShizukuManager.execCommand("settings", "put", "system", "screen_off_timeout", targetTimeout.toString())
                    ShizukuManager.execCommand("svc", "power", "stayon", if (enable) "true" else "false")
                    if (res.isSuccess) return true
                }
                false
            }
            else -> false
        }
    }

    private fun takeScreenshotAction() {
        dismissSwitcher()
        val appContext = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            delay(250L)
            var saved = false
            if (ShizukuManager.hasShizukuPermission()) {
                val dir = "/sdcard/Pictures/Screenshots"
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                val filename = "Screenshot_${sdf.format(java.util.Date())}.png"
                val fullPath = "$dir/$filename"
                ShizukuManager.execCommand("mkdir", "-p", dir)
                val res = ShizukuManager.execCommand("screencap", "-p", fullPath)
                if (res.isSuccess) {
                    saved = true
                    ShizukuManager.execCommand("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://$fullPath")
                } else {
                    val keyRes = ShizukuManager.execCommand("input", "keyevent", "120")
                    saved = keyRes.isSuccess
                }
            } else {
                try {
                    val intent = Intent("android.intent.action.SCREENSHOT")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    saved = true
                } catch (ignored: Throwable) {}
            }
            withContext(Dispatchers.Main) {
                if (saved) {
                    android.widget.Toast.makeText(appContext, "Screenshot captured", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(appContext, "Shizuku required for screenshot capture", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleScreenRecordAction() {
        val helper = com.example.consoleswitcher3.shizuku.ScreenRecordHelper
        if (helper.isRecording) {
            helper.stopRecording(this)
            refreshControlCards()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? android.media.projection.MediaProjectionManager
        if (projectionManager == null) {
            android.widget.Toast.makeText(
                applicationContext,
                "Screen recording unavailable",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                helper.preparePermissionsViaShizuku(this@OverlayActivity)
            }
            if (!isFinishing && !isDestroyed) {
                screenRecordLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun applyControlFallbackOnMain(id: String, enable: Boolean) {
        if (HandheldController.isHandheldControl(id)) {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:$packageName")
                    )
                } else {
                    packageManager.getLaunchIntentForPackage("com.rp.settings")
                        ?: Intent(Settings.ACTION_SETTINGS)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Handheld fallback failed for $id: ${e.message}")
            }
            return
        }
        try {
            val intent = when (id) {
                "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                "bluetooth" -> if (!enable) {
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                } else {
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                }
                "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                "dark_mode" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "rotation" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "battery_saver" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                } else {
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                }
                "night_light" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
                } else {
                    Intent(Settings.ACTION_DISPLAY_SETTINGS)
                }
                "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                "screen_timeout" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                else -> null
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback action failed for $id: ${e.message}")
        }
    }

    private fun findControlCenterPosition(): Int? {
        if (binding.rvControlCenter.width <= 0 || controlAdapter.itemCount == 0) return null
        val centerX = binding.rvControlCenter.width / 2f
        var bestPos: Int? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until binding.rvControlCenter.childCount) {
            val child = binding.rvControlCenter.getChildAt(i) ?: continue
            if (child.right <= child.left) continue
            val childCenter = (child.left + child.right) / 2f
            val dist = kotlin.math.abs(centerX - childCenter)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = controlLayoutManager.getPosition(child)
            }
        }
        return bestPos
    }

    private fun updateControlCenterPadding() {
        val rvWidth = binding.rvControlCenter.width.takeIf { it > 0 }
            ?: binding.overlayRoot.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        if (rvWidth > 0) {
            val itemW = getControlItemWidthPx()
            val padding = ((rvWidth - itemW) / 2).coerceAtLeast(0)
            if (binding.rvControlCenter.paddingStart != padding || binding.rvControlCenter.paddingEnd != padding) {
                binding.rvControlCenter.setPadding(padding, 0, padding, 0)
                binding.rvControlCenter.clipToPadding = false
                binding.rvControlCenter.clipChildren = false
            }
        }
    }

    private fun updateControlXmbTransforms() {
        if (binding.rvControlCenter.width <= 0) return
        for (i in 0 until binding.rvControlCenter.childCount) {
            val child = binding.rvControlCenter.getChildAt(i) ?: continue
            applyControlXmbTransform(child)
        }
    }

    private fun applyControlXmbTransform(child: View) {
        if (child.right <= child.left || child.height <= 0) return
        val centerX = binding.rvControlCenter.width / 2f
        if (centerX <= 0f) return

        val childCenter = (child.left + child.right) / 2f
        val signed = centerX - childCenter
        val distance = Math.abs(signed)

        if (child.width > 0 && child.height > 0) {
            child.pivotX = child.width / 2f
            val cardBottom = child.findViewById<View>(R.id.cardContainer)?.bottom
            child.pivotY = if (cardBottom != null && cardBottom > 0) {
                cardBottom.toFloat().coerceAtMost(child.height.toFloat())
            } else {
                child.height.toFloat()
            }
        }

        val cardWidth = child.width.toFloat()
        if (cardWidth <= 0f) return

        val fraction = (distance / cardWidth).coerceIn(0f, 1f)
        val scale = 1.0f - (fraction * (1.0f - sideScale))
        child.scaleX = scale
        child.scaleY = scale

        val extra = cardWidth * (1.0f - sideScale) / 2f
        val pull = if (distance <= cardWidth) {
            distance / cardWidth
        } else {
            (2f * distance / cardWidth) - 1f
        }
        child.translationX = Math.signum(signed) * extra * pull

        if (!isEntranceAnimating && child.alpha != 1f) child.alpha = 1f

        val wantZ = if (distance < (child.width * 0.35f)) 20f else 0f
        if (child.translationZ != wantZ) child.translationZ = wantZ

        val isCenter = distance < (child.width * 0.35f)
        val holder = binding.rvControlCenter.getChildViewHolder(child) as? DeckControlAdapter.ControlViewHolder
        holder?.setCenterFocused(isCenter)
    }

    private fun smoothScrollControlToCenter(position: Int, isJumpFast: Boolean = false) {
        if (position !in controlCards.indices || position !in 0 until controlAdapter.itemCount) return
        controlPendingTarget = position
        triggerHaptic()

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        if (prefs.isAnimOff(prefs.getAnimSpeed(this))) {
            jumpControlToCenter(position)
            controlPendingTarget = null
            controlProgrammaticScroll = false
            return
        }
        controlProgrammaticScroll = true
        val baseSpeed = prefs.effectiveSpeed(prefs.getAnimSpeed(this))
        val speed = if (isJumpFast) baseSpeed * 2.2f else baseSpeed
        val maxScrollTime = if (isJumpFast) 160 else 350
        val widthRatio = (getItemWidthPx().toFloat() / getControlItemWidthPx().toFloat().coerceAtLeast(1f))
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return ((80f / displayMetrics.densityDpi) / speed) * widthRatio
            }

            override fun calculateTimeForScrolling(dx: Int): Int {
                return super.calculateTimeForScrolling(dx).coerceAtMost(maxScrollTime)
            }
        }
        scroller.targetPosition = position
        controlLayoutManager.startSmoothScroll(scroller)
    }

    private fun jumpControlToCenter(position: Int) {
        if (position !in controlCards.indices) return
        suppressIdleCommit = true
        try {
            controlPendingTarget = null
            controlProgrammaticScroll = false
            binding.rvControlCenter.stopScroll()
        } finally {
            suppressIdleCommit = false
        }
        controlAdapter.selectedPosition = position

        val rvW = binding.rvControlCenter.width
        if (rvW <= 0) {
            controlLayoutManager.scrollToPosition(position)
            binding.rvControlCenter.post { updateControlXmbTransforms() }
            return
        }

        val child = controlLayoutManager.findViewByPosition(position)
        if (child != null && child.width > 0 && child.right > child.left) {
            val childCenter = (child.left + child.right) / 2f
            val centerX = rvW / 2f
            val dx = Math.round(childCenter - centerX)
            if (dx != 0) {
                binding.rvControlCenter.scrollBy(dx, 0)
            }
            updateControlXmbTransforms()
        } else {
            val itemW = getControlItemWidthPx()
            val offset = ((rvW - itemW) / 2 - binding.rvControlCenter.paddingStart)
            try {
                controlLayoutManager.scrollToPositionWithOffset(position, offset)
            } catch (ignored: Throwable) {
                controlLayoutManager.scrollToPosition(position)
            }
            binding.rvControlCenter.doOnNextLayout {
                updateControlXmbTransforms()
            }
            binding.rvControlCenter.post { updateControlXmbTransforms() }
        }
    }

    private fun moveControlSelection(delta: Int) {
        if (controlCards.isEmpty()) return
        val base = controlPendingTarget ?: controlAdapter.selectedPosition
        val newPos = (base + delta).coerceIn(0, controlCards.size - 1)
        if (newPos != base) {
            smoothScrollControlToCenter(newPos)
        }
    }

    private fun switchOverlayMode(targetMode: OverlayMode, slideUp: Boolean) {
        if (currentMode == targetMode || isTransitioningMode || dismissAfterLastFlyOut) return

        if (targetMode == OverlayMode.CONTROL_CENTER) {
            refreshControlCards()
            if (controlCards.isEmpty()) return
        }

        isTransitioningMode = true
        triggerHaptic()

        val outgoingView: View = if (currentMode == OverlayMode.APP_SWITCHER) {
            if (tasks.isEmpty()) binding.emptyLayout else binding.rvTasks
        } else {
            binding.rvControlCenter
        }

        val incomingView: View = if (targetMode == OverlayMode.APP_SWITCHER) {
            if (tasks.isEmpty()) binding.emptyLayout else binding.rvTasks
        } else {
            binding.rvControlCenter
        }

        // Settle any in-flight card removal before sliding the whole strip out.
        queuedKillPresses = 0
        pendingCollapse = null
        cancelCollapse()
        clearRemovalShifts()
        taskRemovalAnimator.endAnimations()

        // Capture intended target positions before stopping active scrolls so neither
        // carousel is ever left stranded halfway between two cards.
        val targetTaskPos = if (tasks.isNotEmpty()) {
            (pendingTarget ?: pendingTargetIndexAfterRemove.takeIf { it >= 0 } ?: findCenterPosition() ?: taskAdapter.selectedPosition)
                .coerceIn(0, tasks.size - 1)
        } else 0
        val targetControlPos = if (controlCards.isNotEmpty()) {
            (controlPendingTarget ?: findControlCenterPosition() ?: controlAdapter.selectedPosition)
                .coerceIn(0, controlCards.size - 1)
        } else 0

        // Cancel any active scrolls before transitioning with suppressIdleCommit so
        // RecyclerView.stopScroll() doesn't re-entrantly start & abort a snap scroll.
        suppressIdleCommit = true
        try {
            pendingTarget = null
            programmaticScroll = false
            controlPendingTarget = null
            controlProgrammaticScroll = false
            binding.rvTasks.stopScroll()
            binding.rvControlCenter.stopScroll()
        } finally {
            suppressIdleCommit = false
        }

        if (tasks.isNotEmpty()) {
            taskAdapter.selectedPosition = targetTaskPos
        }
        if (controlCards.isNotEmpty()) {
            controlAdapter.selectedPosition = targetControlPos
        }

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        modeTransitionAnimator?.cancel()
        modeTransitionAnimator = null

        val hiddenVisibility = if (outgoingView === binding.emptyLayout) View.GONE else View.INVISIBLE

        if (prefs.isCloseAnimOff(closeSpeed)) {
            outgoingView.visibility = hiddenVisibility
            outgoingView.translationY = 0f
            outgoingView.alpha = 1f

            if (tasks.isNotEmpty()) {
                jumpToCenter(targetTaskPos)
            }
            if (controlCards.isNotEmpty()) {
                jumpControlToCenter(targetControlPos)
            }

            incomingView.translationY = 0f
            incomingView.alpha = 1f
            incomingView.visibility = View.VISIBLE
            currentMode = targetMode
            isTransitioningMode = false
            if (targetMode == OverlayMode.CONTROL_CENTER) {
                updateControlXmbTransforms()
            } else if (tasks.isNotEmpty()) {
                updateXmbTransforms()
            }
            return
        }

        val closeSpeedMult = (closeSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_CLOSE_SPEED).coerceIn(0.5f, 3.0f)
        val duration = (220L / closeSpeedMult).toLong().coerceIn(60L, 600L)

        val baseHeight = outgoingView.height.takeIf { it > 0 }
            ?: incomingView.height.takeIf { it > 0 }
            ?: (getCardHeightPx() + (50 * resources.displayMetrics.density).toInt())
        val flyDistance = baseHeight.toFloat() * 1.4f

        val outgoingTargetY = if (slideUp) -flyDistance else flyDistance
        val incomingStartY = if (slideUp) getEntranceStartY() else -getEntranceStartY()

        // Ensure incoming carousel is snapped to exact center before it becomes visible
        if (targetMode == OverlayMode.CONTROL_CENTER) {
            if (controlCards.isNotEmpty()) {
                jumpControlToCenter(targetControlPos)
            }
            updateControlCenterPadding()
            updateControlXmbTransforms()
        } else if (tasks.isNotEmpty()) {
            jumpToCenter(targetTaskPos)
            updateCenterPadding()
            updateXmbTransforms()
        }

        outgoingView.translationY = 0f
        outgoingView.alpha = 1f

        incomingView.translationY = 0f
        incomingView.alpha = 1f
        incomingView.visibility = View.VISIBLE
        currentMode = targetMode

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                if (isFinishing || isDestroyed) return@addUpdateListener
                val f = anim.animatedValue as Float
                val fadeProgress = (f * 1.6f).coerceIn(0f, 1f)
                outgoingView.translationY = outgoingTargetY * f
                outgoingView.alpha = 1f - fadeProgress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modeTransitionAnimator = null
                    outgoingView.visibility = hiddenVisibility
                    outgoingView.translationY = 0f
                    outgoingView.alpha = 1f

                    // Snap the now-hidden outgoing carousel to its target card so it is
                    // already aligned when switching back later.
                    if (targetMode == OverlayMode.CONTROL_CENTER) {
                        if (tasks.isNotEmpty()) {
                            jumpToCenter(targetTaskPos)
                        }
                    } else if (controlCards.isNotEmpty()) {
                        jumpControlToCenter(targetControlPos)
                    }

                    isTransitioningMode = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    modeTransitionAnimator = null
                    isTransitioningMode = false
                }
            })
        }
        modeTransitionAnimator = animator
        animator.start()

        if (targetMode == OverlayMode.CONTROL_CENTER) {
            if (controlCards.isNotEmpty()) {
                playStaggeredEntrance(binding.rvControlCenter, controlLayoutManager, targetControlPos, incomingStartY)
            }
        } else {
            if (tasks.isNotEmpty()) {
                playStaggeredEntrance(binding.rvTasks, layoutManager, targetTaskPos, incomingStartY)
            }
        }
    }

    private fun setupListeners() {
        // Tap outside to dismiss
        binding.overlayRoot.setOnClickListener {
            if (isTransitioningMode) return@setOnClickListener
            dismissSwitcher()
        }

        binding.emptyLayout.setOnClickListener {
            openSettings()
        }
    }

    private fun loadTasks() {
        binding.emptyLayout.visibility = View.GONE
        binding.rvTasks.visibility = View.INVISIBLE

        lifecycleScope.launch {
            var currentTasks = filterRecentlyClosed(TaskManager.getRecentTasks(this@OverlayActivity))
            // Full refresh: settle any close still animating against the old list.
            resetTaskRemovalState()
            tasks.clear()
            tasks.addAll(currentTasks)

            if (tasks.isEmpty()) {
                val hasPermission = ShizukuManager.hasShizukuPermission()
                if (!hasPermission) {
                    binding.ivEmptyIcon.setImageResource(R.drawable.ic_allergy_rounded)
                    binding.tvEmptyMessage.setText(R.string.shizuku_required)
                } else {
                    binding.ivEmptyIcon.setImageResource(R.drawable.ic_sentiment_neutral_rounded)
                    binding.tvEmptyMessage.setText(R.string.no_recent_items)
                }
                binding.rvTasks.visibility = View.INVISIBLE
                binding.emptyLayout.visibility = if (currentMode == OverlayMode.APP_SWITCHER) View.VISIBLE else View.GONE
            } else {
                binding.emptyLayout.visibility = View.GONE
                val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
                val navSpeed = prefs.getAnimSpeed(this@OverlayActivity)
                if (!prefs.isAnimOff(navSpeed) && !hasPlayedEntranceAnimation) {
                    isEntranceAnimating = true
                }
                taskAdapter.updateTasks(tasks)

                taskAdapter.selectedPosition = 0
                pendingTarget = null
                programmaticScroll = false
                updateCenterPadding()
                jumpToCenter(0)
                binding.rvTasks.visibility = if (currentMode == OverlayMode.APP_SWITCHER) View.VISIBLE else View.INVISIBLE
                startEntranceAnimation()
            }

            // Immediate background pass for any off-screen cards (index >= 4) whose snapshots
            // weren't cached yet, so the strip opens instantly without waiting on all 30 tasks.
            if (currentTasks.any { it.previewBitmap == null && it.taskId > 0 && !it.isCurrentTask }) {
                currentTasks = TaskManager.loadMissingSnapshots(currentTasks)
                applyRefreshedTaskPreviews(currentTasks)
            }

            // Second pass after 600ms: the app that was foreground when the overlay opened
            // may need a moment for the system to write its snapshot in the background.
            if (currentTasks.any { it.previewBitmap == null && it.taskId > 0 && !it.isCurrentTask }) {
                val refreshed = TaskManager.refreshMissingPreviews(currentTasks)
                applyRefreshedTaskPreviews(refreshed)
            }
        }
    }

    private fun applyRefreshedTaskPreviews(refreshed: List<DeckTask>) {
        if (refreshed.none { it.previewBitmap != null }) return
        taskAdapter.updatePreviewsIfGained(refreshed)
        val byId = refreshed.associateBy { it.taskId }
        for (i in tasks.indices) {
            val cur = tasks[i]
            if (!cur.isCurrentTask && cur.previewBitmap == null) {
                val updated = byId[cur.taskId]
                if (updated?.previewBitmap != null) {
                    tasks[i] = cur.copy(previewBitmap = updated.previewBitmap)
                }
            }
        }
        binding.rvTasks.post { updateXmbTransforms() }
    }

    private fun getEntranceStartY(): Float {
        val density = resources.displayMetrics.density
        val rvHeight = binding.rvTasks.height.toFloat()
        val cardH = getCardHeightPx().toFloat()
        return maxOf(rvHeight, cardH + (80f * density)) + (40f * density)
    }

    private fun startEntranceAnimation() {
        if (hasPlayedEntranceAnimation || tasks.isEmpty() || currentMode != OverlayMode.APP_SWITCHER) return
        hasPlayedEntranceAnimation = true

        val centerPos = taskAdapter.selectedPosition.coerceIn(0, tasks.size - 1)
        playStaggeredEntrance(binding.rvTasks, layoutManager, centerPos, getEntranceStartY())
    }

    private fun cancelEntranceSafetyRunnable() {
        entranceSafetyRunnable?.let {
            binding.rvTasks.removeCallbacks(it)
            binding.rvControlCenter.removeCallbacks(it)
        }
        entranceSafetyRunnable = null
    }

    private fun computeEntranceTiming(): Pair<Long, Long>? {
        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val navSpeed = prefs.getAnimSpeed(this)
        if (prefs.isAnimOff(navSpeed)) return null
        val navSpeedMult = (navSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_NAV_SPEED).coerceIn(0.5f, 3.0f)
        val duration = (340L / navSpeedMult).toLong().coerceIn(150L, 600L)
        val stepDelay = (60L / navSpeedMult).toLong().coerceIn(25L, 150L)
        return duration to stepDelay
    }

    private fun onEntranceChildAttached(
        recyclerView: RecyclerView,
        lm: LinearLayoutManager,
        view: View
    ) {
        if (!isEntranceAnimating) return
        if (isWaitingForEntranceLayout) {
            view.animate().cancel()
            view.translationY = currentEntranceStartY
            view.alpha = 0f
            return
        }
        val timing = computeEntranceTiming() ?: return
        val pos = lm.getPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        startCardEntranceAnimator(
            recyclerView = recyclerView,
            child = view,
            pos = pos,
            startOffsetY = currentEntranceStartY,
            duration = timing.first,
            stepDelay = timing.second,
            generation = entranceGeneration
        )
    }

    private fun startCardEntranceAnimator(
        recyclerView: RecyclerView,
        child: View,
        pos: Int,
        startOffsetY: Float,
        duration: Long,
        stepDelay: Long,
        generation: Int
    ) {
        val distance = Math.abs(pos - currentEntranceCenterPos)
        val delay = (distance * stepDelay).coerceAtLeast(0L)

        child.animate().cancel()
        child.translationY = startOffsetY
        child.alpha = 0f

        entrancePendingCount++
        val anim = child.animate()
            .translationY(0f)
            .alpha(1f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction {
                child.translationY = 0f
                child.alpha = 1f
                if (generation != entranceGeneration) return@withEndAction
                entrancePendingCount--
                if (entrancePendingCount <= 0) {
                    isEntranceAnimating = false
                    entranceAnimators.clear()
                    cancelEntranceSafetyRunnable()
                    if (recyclerView === binding.rvControlCenter) {
                        updateControlXmbTransforms()
                    } else {
                        updateXmbTransforms()
                    }
                }
            }
        entranceAnimators.add(anim)
        anim.start()
    }

    private fun playStaggeredEntrance(
        recyclerView: RecyclerView,
        layoutManager: LinearLayoutManager,
        centerPos: Int,
        startOffsetY: Float
    ) {
        if (computeEntranceTiming() == null) {
            finishEntranceAnimation()
            return
        }

        entranceGeneration++
        val generation = entranceGeneration
        cancelEntranceSafetyRunnable()
        entranceAnimators.forEach { it.cancel() }
        entranceAnimators.clear()
        entrancePendingCount = 0

        isEntranceAnimating = true
        currentEntranceCenterPos = centerPos
        currentEntranceStartY = startOffsetY

        val centerView = layoutManager.findViewByPosition(centerPos)
        val needsLayout = recyclerView.isLayoutRequested ||
                recyclerView.childCount == 0 ||
                centerView == null ||
                centerView.width <= 0

        if (needsLayout) {
            isWaitingForEntranceLayout = true
            // Hide current children immediately so stale cards from previous scroll don't flash
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i) ?: continue
                if (taskRemovalAnimator.isFlyingOut(child)) continue
                child.animate().cancel()
                child.alpha = 0f
            }
            recyclerView.doOnNextLayout {
                if (generation != entranceGeneration || !isEntranceAnimating) return@doOnNextLayout
                isWaitingForEntranceLayout = false
                if (recyclerView === binding.rvControlCenter) {
                    updateControlXmbTransforms()
                } else {
                    updateXmbTransforms()
                }
                playStaggeredEntranceInternal(recyclerView, layoutManager, centerPos, startOffsetY, generation)
            }
            return
        }

        isWaitingForEntranceLayout = false
        playStaggeredEntranceInternal(recyclerView, layoutManager, centerPos, startOffsetY, generation)
    }

    private fun playStaggeredEntranceInternal(
        recyclerView: RecyclerView,
        layoutManager: LinearLayoutManager,
        centerPos: Int,
        startOffsetY: Float,
        generation: Int
    ) {
        if (generation != entranceGeneration || !isEntranceAnimating) return

        val count = recyclerView.childCount
        if (count == 0) {
            isEntranceAnimating = false
            return
        }

        val timing = computeEntranceTiming()
        if (timing == null) {
            finishEntranceAnimation()
            return
        }
        val (duration, stepDelay) = timing

        currentEntranceCenterPos = centerPos
        currentEntranceStartY = startOffsetY
        entranceAnimators.forEach { it.cancel() }
        entranceAnimators.clear()
        entrancePendingCount = 0
        cancelEntranceSafetyRunnable()

        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i) ?: continue
            if (taskRemovalAnimator.isFlyingOut(child)) continue
            val pos = layoutManager.getPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            startCardEntranceAnimator(recyclerView, child, pos, startOffsetY, duration, stepDelay, generation)
        }

        if (entrancePendingCount == 0) {
            isEntranceAnimating = false
        } else {
            val maxDelay = (count * stepDelay)
            val safetyTimeout = maxDelay + duration + 200L
            val runnable = Runnable {
                if (generation == entranceGeneration && isEntranceAnimating) {
                    finishEntranceAnimation()
                }
            }
            entranceSafetyRunnable = runnable
            recyclerView.postDelayed(runnable, safetyTimeout)
        }
    }

    private fun finishEntranceAnimation() {
        entranceGeneration++
        isWaitingForEntranceLayout = false
        entrancePendingCount = 0
        cancelEntranceSafetyRunnable()
        if (!isEntranceAnimating) return
        isEntranceAnimating = false
        entranceAnimators.forEach { it.cancel() }
        entranceAnimators.clear()
        for (i in 0 until binding.rvTasks.childCount) {
            val child = binding.rvTasks.getChildAt(i) ?: continue
            if (taskRemovalAnimator.isFlyingOut(child)) continue
            child.animate().cancel()
            child.translationY = 0f
            child.alpha = 1f
        }
        for (i in 0 until binding.rvControlCenter.childCount) {
            val child = binding.rvControlCenter.getChildAt(i) ?: continue
            child.animate().cancel()
            child.translationY = 0f
            child.alpha = 1f
        }
        if (currentMode == OverlayMode.CONTROL_CENTER) {
            updateControlXmbTransforms()
        } else {
            updateXmbTransforms()
        }
    }

    /**
     * Centers the target item. Animated with the Settings speed, or jumped
     * to instantly when the slider's final "off" detent is selected.
     * Commits nothing up front: selectedPosition is only updated when the
     * scroll settles (idle handler), so recycled views binding mid-flight
     * don't flash the target's focused border early.
     */
    private fun smoothScrollToCenter(position: Int, isJumpFast: Boolean = false) {
        if (position !in tasks.indices || position !in 0 until taskAdapter.itemCount) {
            android.util.Log.w(TAG, "smoothScrollToCenter: invalid target=$position tasks=${tasks.size} adapter=${taskAdapter.itemCount}")
            return
        }
        android.util.Log.d(TAG, "smoothScrollToCenter: target=$position count=${taskAdapter.itemCount} fast=$isJumpFast")
        pendingTarget = position
        triggerHaptic()

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        if (prefs.isAnimOff(prefs.getAnimSpeed(this))) {
            jumpToCenter(position)
            pendingTarget = null
            programmaticScroll = false
            return
        }
        programmaticScroll = true
        val baseSpeed = prefs.effectiveSpeed(prefs.getAnimSpeed(this))
        val speed = if (isJumpFast) baseSpeed * 2.2f else baseSpeed
        val maxScrollTime = if (isJumpFast) 160 else 350
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return (80f / displayMetrics.densityDpi) / speed
            }

            override fun calculateTimeForScrolling(dx: Int): Int {
                return super.calculateTimeForScrolling(dx).coerceAtMost(maxScrollTime)
            }
        }
        scroller.targetPosition = position
        if (pendingTargetIndexAfterRemove >= 0) {
            // A removal in this frame hasn't been laid out yet: let onLayoutCompleted
            // anchor the post-removal layout and start the collapse first, then scroll.
            binding.rvTasks.doOnNextLayout {
                if (pendingTarget == position && position in 0 until taskAdapter.itemCount) {
                    layoutManager.startSmoothScroll(scroller)
                }
            }
        } else {
            layoutManager.startSmoothScroll(scroller)
        }
    }

    /**
     * Instant (no-animation) centering for the "animation off" detent and first
     * layout. Plain scrollToPosition() only guarantees visibility, which can
     * leave the strip off-center with visual focus disagreeing with selection.
     * When the target view is already attached, scrollBy() centers it synchronously
     * in the exact same frame. Otherwise scrollToPositionWithOffset() is used
     * and transforms are bound immediately upon layout.
     */
    private fun jumpToCenter(position: Int) {
        if (position !in tasks.indices) return
        pendingCollapse = null
        cancelCollapse()
        clearRemovalShifts()
        suppressIdleCommit = true
        try {
            pendingTarget = null
            programmaticScroll = false
            binding.rvTasks.stopScroll()
        } finally {
            suppressIdleCommit = false
        }
        taskAdapter.selectedPosition = position
        updateCenterPadding()

        val rvW = binding.rvTasks.width
        if (rvW <= 0) {
            pendingTargetIndexAfterRemove = position
            try {
                layoutManager.scrollToPositionWithOffset(position, 0)
            } catch (ignored: Throwable) {
                layoutManager.scrollToPosition(position)
            }
            binding.rvTasks.post { updateXmbTransforms() }
            return
        }

        val child = layoutManager.findViewByPosition(position)
        if (!binding.rvTasks.isLayoutRequested && child != null && child.width > 0 && child.right > child.left) {
            val childCenter = (child.left + child.right) / 2f
            val centerX = rvW / 2f
            val dx = Math.round(childCenter - centerX)
            if (dx != 0) {
                binding.rvTasks.scrollBy(dx, 0)
            }
            updateXmbTransforms()
        } else {
            pendingTargetIndexAfterRemove = position
            val itemW = getItemWidthPx()
            val offset = ((rvW - itemW) / 2 - binding.rvTasks.paddingStart)
            try {
                layoutManager.scrollToPositionWithOffset(position, offset)
            } catch (ignored: Throwable) {
                layoutManager.scrollToPosition(position)
            }
            binding.rvTasks.doOnNextLayout {
                updateXmbTransforms()
            }
            binding.rvTasks.post { updateXmbTransforms() }
        }
    }

    private fun moveSelection(delta: Int) {
        if (dismissAfterLastFlyOut || tasks.isEmpty()) return
        // Chain off the in-flight target so rapid presses walk the strip
        // instead of re-issuing the same step from the last landed index.
        val base = pendingTarget ?: taskAdapter.selectedPosition
        val newPos = (base + delta).coerceIn(0, tasks.size - 1)
        if (newPos != base) {
            smoothScrollToCenter(newPos)
        }
    }

    private fun switchTask(task: DeckTask) {
        finishEntranceAnimation()
        triggerHaptic()
        if (task.isCurrentTask) {
            dismissSwitcher()
            return
        }
        lifecycleScope.launch {
            TaskManager.switchTask(this@OverlayActivity, task)
            dismissSwitcher()
        }
    }

    private fun switchSelectedTask() {
        if (dismissAfterLastFlyOut) return
        val pos = pendingTarget ?: findCenterPosition() ?: taskAdapter.selectedPosition
        val task = taskAdapter.getTaskAt(pos) ?: taskAdapter.getSelectedTask() ?: return
        switchTask(task)
    }

    /** Close presses that arrived before the previous removal was laid out. */
    private var queuedKillPresses = 0

    /** Where a removal's collapse must start from, captured before the data changed. */
    private class PendingCollapse(
        /** Adapter position of the removed card (= new position of its right neighbour). */
        val removedPos: Int,
        /** On-screen center of the left / right neighbour right before the removal. */
        val oldLeftCenter: Float,
        val oldRightCenter: Float,
        val animate: Boolean,
        val duration: Long,
        val startDelay: Long
    )

    /**
     * Closes the visually centered app.
     *
     * The task is removed from the data immediately, so the strip is never
     * locked: navigation, switching, mode changes, Back and further closes all
     * respond on the very next input and always act on the correct card. The
     * visuals catch up independently:
     *  - the closed card keeps flying out via [taskRemovalAnimator] (RecyclerView
     *    keeps it attached until it's done, so it's never recycled mid-flight);
     *  - the survivors start exactly where they are on screen right now (even if
     *    a previous collapse or a scroll is still in progress) and slide into
     *    their new slots, so rapid repeated closes chain into one smooth motion.
     */
    private fun killSelectedTask() {
        if (dismissAfterLastFlyOut || isFinishing || isDestroyed || tasks.isEmpty()) return
        // A removal from a press in this same frame hasn't been laid out yet, so
        // view positions are stale. Run this press right after that layout.
        if (binding.rvTasks.hasPendingAdapterUpdates() || binding.rvTasks.isComputingLayout) {
            queuedKillPresses++
            if (queuedKillPresses == 1) {
                binding.rvTasks.doOnNextLayout { binding.rvTasks.post { drainQueuedKills() } }
            }
            return
        }
        finishEntranceAnimation()

        // Stop any in-flight navigation scroll immediately
        suppressIdleCommit = true
        try {
            pendingTarget = null
            programmaticScroll = false
            binding.rvTasks.stopScroll()
        } finally {
            suppressIdleCommit = false
        }

        // Kill what is VISUALLY centered (border + label + full size are all
        // distance-driven, so the strip center is the selection truth).
        val victimPos = findCenterPosition()
            ?.takeIf { it in tasks.indices }
            ?: taskAdapter.selectedPosition.takeIf { it in tasks.indices }
            ?: return
        val task = taskAdapter.getTaskAt(victimPos) ?: return
        android.util.Log.d(TAG, "kill press: victim=$victimPos selected=${taskAdapter.selectedPosition} count=${taskAdapter.itemCount}")
        triggerHaptic()

        // App-wide scope: closing the overlay right after (Back, or closing the
        // last card) must not cancel a half-finished force-stop.
        recentlyClosedTasks[closedTaskKey(task)] = SystemClock.uptimeMillis()
        val appContext = applicationContext
        killScope.launch {
            try {
                val success = TaskManager.killTask(appContext, task)
                val msg = if (success) "Force stopped ${task.label}" else "Removed ${task.label}"
                android.util.Log.d(TAG, msg)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "killTask error", e)
            }
        }

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        val victimView = layoutManager.findViewByPosition(victimPos)
        val animate = !prefs.isCloseAnimOff(closeSpeed) &&
            victimView != null && victimView.width > 0 && binding.rvTasks.isShown

        // Dismiss (fly-up) speed follows the App closing slider.
        val closeSpeedMult = (closeSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_CLOSE_SPEED).coerceIn(0.5f, 3.0f)
        val flyDuration = (190L / closeSpeedMult).toLong().coerceIn(50L, 600L)
        // Collapse (slide) speed follows the Carousel navigation slider.
        val navSpeed = prefs.getAnimSpeed(this)
        val navSpeedMult = if (prefs.isAnimOff(navSpeed)) 3.0f
            else (navSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_NAV_SPEED).coerceIn(0.5f, 3.0f)
        val slideDuration = (210L / navSpeedMult).toLong().coerceIn(60L, 500L)
        val slideDelayMs = com.example.consoleswitcher3.prefs.OverlayPrefs.getSlideDelayMs(this).toLong()

        // Snapshot where the neighbours are on screen BEFORE the data changes,
        // including any collapse offset still in flight from a previous close.
        val centerX = binding.rvTasks.width / 2f
        val slotWidth = victimView?.width?.toFloat()?.takeIf { it > 0f } ?: getItemWidthPx().toFloat()
        val victimCenter = victimView?.let { visualCenterOf(it, victimPos) } ?: centerX
        val oldLeftCenter = (if (victimPos > 0) layoutManager.findViewByPosition(victimPos - 1) else null)
            ?.takeIf { it.right > it.left }
            ?.let { visualCenterOf(it, victimPos - 1) }
            ?: (victimCenter - slotWidth)
        val oldRightCenter = layoutManager.findViewByPosition(victimPos + 1)
            ?.takeIf { it.right > it.left }
            ?.let { visualCenterOf(it, victimPos + 1) }
            ?: (victimCenter + slotWidth)

        // Hand the card to the item animator BEFORE its data goes away.
        if (animate) {
            val flyDistance = (binding.rvTasks.height.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels).toFloat() * 1.5f
            startFlyOut(binding.rvTasks.getChildViewHolder(victimView), flyDuration, flyDistance)
        }

        if (binding.rvTasks.height > 0 && binding.rvTasks.minimumHeight != binding.rvTasks.height) {
            binding.rvTasks.minimumHeight = binding.rvTasks.height
        }

        // Commit the removal right away.
        tasks.removeAt(victimPos)

        if (tasks.isEmpty()) {
            pendingCollapse = null
            cancelCollapse()
            clearRemovalShifts()
            if (animate) {
                // Keep the adapter intact during the final fly-out so RecyclerView
                // doesn't run an empty-list layout pass or detach the card mid-flight;
                // the overlay finishes as soon as the fly-out ends (see startFlyOut).
                dismissAfterLastFlyOut = true
            } else {
                taskAdapter.removeAt(victimPos)
                dismissSwitcher()
            }
            return
        }

        taskAdapter.removeAt(victimPos)

        // The right neighbour takes the victim's index; if there was none,
        // the left neighbour becomes the centered card.
        val nextPos = victimPos.coerceAtMost(tasks.size - 1)
        taskAdapter.selectedPosition = nextPos
        // Anchor post-layout directly on nextPos at paddingStart (centerX). Without
        // this, when the centered card is removed, LinearLayoutManager.findReferenceChild
        // sees every other card outside [paddingStart, width - paddingEnd], picks the
        // leftmost attached card (e.g. victimPos - 4) and snaps it to centerX, which
        // when closing the last card on the right leaves nextPos off-screen.
        layoutManager.scrollToPositionWithOffset(nextPos, 0)
        // Lock the surviving target card to exact center inside the layout
        // pass triggered by notifyItemRemoved, then start the collapse there.
        pendingTargetIndexAfterRemove = nextPos
        pendingCollapse = PendingCollapse(
            removedPos = victimPos,
            oldLeftCenter = oldLeftCenter,
            oldRightCenter = oldRightCenter,
            animate = animate,
            duration = slideDuration,
            startDelay = slideDelayMs
        )
    }

    private fun drainQueuedKills() {
        val presses = queuedKillPresses
        queuedKillPresses = 0
        // Each close re-queues itself behind the layout of the previous one.
        repeat(presses) { killSelectedTask() }
    }

    /** Fly-up of a closed card: up, shrink and fade, from wherever it currently is. */
    private fun startFlyOut(holder: RecyclerView.ViewHolder, duration: Long, distance: Float) {
        val view = holder.itemView
        view.animate().cancel()
        view.translationZ = 40f
        val startY = view.translationY
        val startAlpha = view.alpha
        val startScale = view.scaleX
        val endY = -distance
        val endScale = startScale * 0.82f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = AccelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                view.translationY = startY + (endY - startY) * f
                view.alpha = startAlpha * (1f - f)
                val scale = startScale + (endScale - startScale) * f
                view.scaleX = scale
                view.scaleY = scale
            }
        }
        taskRemovalAnimator.startFlyOut(holder, animator) {
            if (dismissAfterLastFlyOut && tasks.isEmpty() && !taskRemovalAnimator.hasActiveFlyOuts() && !isFinishing && !isDestroyed) {
                dismissSwitcher()
            }
        }
    }

    /**
     * Runs inside the layout pass that applied a removal: the survivors already
     * sit in their final slots, so offset them back to where they were on screen
     * and animate that offset away (FLIP). Interrupting a running collapse is
     * seamless because the snapshot included its current offset.
     */
    private fun startCollapse(collapse: PendingCollapse) {
        cancelCollapse()
        clearRemovalShifts()
        if (!collapse.animate) return

        fun layoutCenterAt(pos: Int): Float? {
            if (pos < 0) return null
            val v = layoutManager.findViewByPosition(pos) ?: return null
            if (v.right <= v.left) return null
            return (v.left + v.right) / 2f
        }
        val rightShift = layoutCenterAt(collapse.removedPos)
            ?.let { collapse.oldRightCenter - it } ?: 0f
        val leftShift = layoutCenterAt(collapse.removedPos - 1)
            ?.let { collapse.oldLeftCenter - it } ?: 0f
        if (kotlin.math.abs(rightShift) < 0.5f && kotlin.math.abs(leftShift) < 0.5f) return

        removalShiftStartIndex = if (rightShift != 0f) collapse.removedPos else -1
        removalShiftX = rightShift
        removalLeftShiftEndIndex = if (leftShift != 0f) collapse.removedPos - 1 else -1
        removalLeftShiftX = leftShift

        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = collapse.duration
            startDelay = collapse.startDelay
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                if (collapseAnimator !== anim) return@addUpdateListener
                val f = anim.animatedValue as Float
                removalShiftX = rightShift * f
                removalLeftShiftX = leftShift * f
                updateXmbTransforms()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Superseded by a newer close (or cancelled): it owns the shifts now.
                    if (collapseAnimator !== animation) return
                    collapseAnimator = null
                    clearRemovalShifts()
                    if (!isFinishing && !isDestroyed) {
                        updateXmbTransforms()
                        triggerHaptic()
                    }
                }
            })
        }
        collapseAnimator = animator
        animator.start()
    }

    private fun cancelCollapse() {
        val animator = collapseAnimator ?: return
        collapseAnimator = null
        animator.cancel()
    }

    private fun clearRemovalShifts() {
        removalShiftStartIndex = -1
        removalShiftX = 0f
        removalLeftShiftEndIndex = -1
        removalLeftShiftX = 0f
    }

    /** Collapse offset currently applied to the card at adapter position [pos]. */
    private fun removalShiftFor(pos: Int): Float = when {
        pos == RecyclerView.NO_POSITION -> 0f
        removalShiftStartIndex >= 0 && pos >= removalShiftStartIndex -> removalShiftX
        removalLeftShiftEndIndex >= 0 && pos <= removalLeftShiftEndIndex -> removalLeftShiftX
        else -> 0f
    }

    /** Untransformed on-screen center of [view] (layout center + collapse offset). */
    private fun visualCenterOf(view: View, pos: Int): Float =
        (view.left + view.right) / 2f + removalShiftFor(pos)

    /** Settles every in-flight close instantly (full data refresh / teardown). */
    private fun resetTaskRemovalState() {
        queuedKillPresses = 0
        dismissAfterLastFlyOut = false
        pendingCollapse = null
        cancelCollapse()
        clearRemovalShifts()
        taskRemovalAnimator.endAnimations()
    }

    private fun closedTaskKey(task: DeckTask): String =
        if (task.taskId > 0) "id:${task.taskId}" else "pkg:${task.packageName}"

    /** Drops tasks closed moments ago that the system may still report while being killed. */
    private fun filterRecentlyClosed(list: List<DeckTask>): List<DeckTask> {
        val now = SystemClock.uptimeMillis()
        recentlyClosedTasks.values.removeAll { now - it > RECENTLY_CLOSED_TTL_MS }
        if (recentlyClosedTasks.isEmpty()) return list
        return list.filter { !recentlyClosedTasks.containsKey(closedTaskKey(it)) }
    }

    private fun dismissSwitcher() {
        if (isFinishing) return
        dismissAfterLastFlyOut = false
        queuedKillPresses = 0
        pendingCollapse = null
        cancelCollapse()
        if (tasks.isEmpty()) {
            binding.rvTasks.visibility = View.INVISIBLE
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_settings", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        dismissSwitcher()
    }

    override fun onStart() {
        super.onStart()
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(volumeReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register volume receiver: ${e.message}")
        }
        try {
            val controlFilter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                @Suppress("DEPRECATION")
                addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.hid.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED")
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlCenterReceiver, controlFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(controlCenterReceiver, controlFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register control center receiver: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        resetTaskRemovalState()
        finishEntranceAnimation()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (ignored: Throwable) {}
        try {
            unregisterReceiver(controlCenterReceiver)
        } catch (ignored: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        dismissAfterLastFlyOut = false
        isTransitioningMode = false
        initBrightness()
        lastReportedVolume = getVolumePercent()
        refreshControlCards()
        if (currentMode == OverlayMode.CONTROL_CENTER && controlCards.isEmpty()) {
            switchOverlayMode(OverlayMode.APP_SWITCHER, slideUp = false)
        }
        if (isBluetoothMenuOpen) {
            applyBluetoothModalStyling()
            refreshBluetoothMenuUi()
        } else if (isWifiMenuOpen) {
            applyBluetoothModalStyling()
            refreshWifiMenuUi()
        } else if (isHandheldModalOpen) {
            applyBluetoothModalStyling()
            refreshHandheldModalUi()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasPlayedEntranceAnimation = false
        loadTasks()
    }

    override fun onPause() {
        super.onPause()
        isTransitioningMode = false
        gamepadHelper.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetTaskRemovalState()
        finishEntranceAnimation()
        isTransitioningMode = false
        closeBluetoothProfiles()
        gamepadHelper.reset()
        indicatorHandler.removeCallbacksAndMessages(null)
        binding.layoutBrightness.animate().cancel()
        binding.layoutVolume.animate().cancel()
        binding.layoutMedia.animate().cancel()
        modeTransitionAnimator?.cancel()
        modeTransitionAnimator = null
    }

    private fun initBrightness() {
        currentBrightness = try {
            Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
    }

    private fun showBrightnessIndicator() {
        val percent = ((currentBrightness / 255.0f) * 100f).roundToInt().coerceIn(0, 100)
        binding.tvBrightness.text = "$percent%"

        // Dismiss volume and media if currently showing
        indicatorHandler.removeCallbacks(hideVolumeRunnable)
        binding.layoutVolume.animate().cancel()
        binding.layoutVolume.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideMediaRunnable)
        binding.layoutMedia.animate().cancel()
        binding.layoutMedia.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        binding.layoutBrightness.animate().cancel()
        binding.layoutBrightness.alpha = 1f
        binding.layoutBrightness.visibility = View.VISIBLE
        indicatorHandler.postDelayed(hideBrightnessRunnable, INDICATOR_TIMEOUT_MS)
    }

    private fun getVolumePercent(): Int {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 0
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((currentVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun showVolumeIndicator() {
        val percent = getVolumePercent()
        lastReportedVolume = percent
        binding.tvVolume.text = "$percent%"

        // Dismiss brightness and media if currently showing
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        binding.layoutBrightness.animate().cancel()
        binding.layoutBrightness.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideMediaRunnable)
        binding.layoutMedia.animate().cancel()
        binding.layoutMedia.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideVolumeRunnable)
        binding.layoutVolume.animate().cancel()
        binding.layoutVolume.alpha = 1f
        binding.layoutVolume.visibility = View.VISIBLE
        indicatorHandler.postDelayed(hideVolumeRunnable, INDICATOR_TIMEOUT_MS)
    }

    private fun adjustVolume(direction: Int) {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                0
            )
            showVolumeIndicator()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
        }
    }

    private fun showMediaIndicator(text: String) {
        binding.tvMedia.text = text

        // Dismiss brightness and volume if currently showing
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        binding.layoutBrightness.animate().cancel()
        binding.layoutBrightness.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideVolumeRunnable)
        binding.layoutVolume.animate().cancel()
        binding.layoutVolume.visibility = View.GONE

        indicatorHandler.removeCallbacks(hideMediaRunnable)
        binding.layoutMedia.animate().cancel()
        binding.layoutMedia.alpha = 1f
        binding.layoutMedia.visibility = View.VISIBLE
        indicatorHandler.postDelayed(hideMediaRunnable, INDICATOR_TIMEOUT_MS)
    }

    private var lastMediaToggleTime = 0L

    private fun toggleBackgroundMedia() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMediaToggleTime < 350L) return
        lastMediaToggleTime = now

        triggerHaptic()
        lifecycleScope.launch(Dispatchers.Main) {
            val isPlaying = audioManager.isMusicActive
            if (isPlaying) {
                // Audio is currently playing -> explicitly pause it
                showMediaIndicator("Paused")
                withContext(Dispatchers.IO) {
                    sendMediaCommandSync(play = false)
                }
            } else {
                // Audio is not playing -> check if a paused session exists, send play,
                // and verify that playback actually starts before showing "Unpaused".
                val startedPlaying = withContext(Dispatchers.IO) {
                    val hasShizuku = ShizukuManager.hasShizukuPermission()
                    if (hasShizuku) {
                        val hadPausedSession = hasActiveSessionInState(paused = true)
                        if (!hadPausedSession) {
                            return@withContext false
                        }
                        sendMediaCommandSync(play = true)
                        for (i in 0 until 8) {
                            delay(45)
                            if (audioManager.isMusicActive) {
                                return@withContext true
                            }
                        }
                        hasActiveSessionInState(paused = false)
                    } else {
                        sendMediaCommandSync(play = true)
                        for (i in 0 until 8) {
                            delay(45)
                            if (audioManager.isMusicActive) {
                                return@withContext true
                            }
                        }
                        false
                    }
                }

                if (startedPlaying) {
                    showMediaIndicator("Unpaused")
                } else {
                    showMediaIndicator("No Media")
                }
            }
        }
    }

    private suspend fun sendMediaCommandSync(play: Boolean) {
        val keyCode = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        if (ShizukuManager.hasShizukuPermission()) {
            try {
                val cmd = if (play) "play" else "pause"
                val res = ShizukuManager.execCommand("cmd", "media_session", "dispatch", cmd)
                if (res.isSuccess) return
            } catch (ignored: Throwable) {}
        }
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (ignored: Throwable) {}
    }

    /**
     * Checks `dumpsys media_session` for an active, non-system media session with
     * non-empty metadata that is currently in PAUSED (`paused = true`) or PLAYING
     * (`paused = false`) state.
     */
    private suspend fun hasActiveSessionInState(paused: Boolean): Boolean {
        if (!ShizukuManager.hasShizukuPermission()) return false
        try {
            val dumpRes = ShizukuManager.execCommand("dumpsys", "media_session")
            if (!dumpRes.isSuccess || dumpRes.output.isBlank()) return false

            val blocks = dumpRes.output.split("ownerPid=")
            for (i in 1 until blocks.size) {
                val block = blocks[i]
                if (block.contains("package=com.android.bluetooth") ||
                    block.contains("package=com.android.systemui") ||
                    block.contains("package=com.android.server.telecom")
                ) {
                    continue
                }
                val isActive = block.contains("active=true")
                if (!isActive) continue

                val hasMetadata = block.contains("metadata:") &&
                    !block.contains("metadata: null") &&
                    !block.contains("metadata: size=0")
                if (!hasMetadata) continue

                val matchesState = if (paused) {
                    block.contains("PlaybackState {state=2,") ||
                        block.contains("PlaybackState {state=PAUSED(2)")
                } else {
                    block.contains("PlaybackState {state=3,") ||
                        block.contains("PlaybackState {state=PLAYING(3)")
                }
                if (matchesState) return true
            }
        } catch (ignored: Throwable) {}
        return false
    }

    private fun changeBrightness(delta: Int) {
        currentBrightness = (currentBrightness + delta).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

        // 1. Instantly update current activity window brightness
        val lp = window.attributes
        lp.screenBrightness = currentBrightness / 255.0f
        window.attributes = lp

        // 2. Persist to system setting
        persistSystemBrightness(currentBrightness)

        // 3. Update indicator
        showBrightnessIndicator()
    }

    private fun persistSystemBrightness(brightness: Int) {
        // Attempt 1: Standard Settings.System if WRITE_SETTINGS is granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct Settings.System write failed: ${e.message}")
            }
        }

        // Attempt 2: Via Shizuku if granted
        if (ShizukuManager.hasShizukuPermission()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ShizukuManager.execCommand("settings", "put", "system", "screen_brightness_mode", "0")
                    ShizukuManager.execCommand("settings", "put", "system", "screen_brightness", brightness.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku settings put failed: ${e.message}")
                }
            }
        }
    }

    // Gamepad Key Events
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_MENU)) {
            openSettings()
            return true
        }
        if (gamepadHelper.handleKeyEvent(event)) {
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                adjustVolume(AudioManager.ADJUST_RAISE)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                adjustVolume(AudioManager.ADJUST_LOWER)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadHelper.handleMotionEvent(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // GamepadListener Callbacks
    override fun onNavLeft() {
        if (isControlModalOpen) return
        if (currentMode == OverlayMode.APP_SWITCHER) {
            moveSelection(-1)
        } else {
            moveControlSelection(-1)
        }
    }

    override fun onNavRight() {
        if (isControlModalOpen) return
        if (currentMode == OverlayMode.APP_SWITCHER) {
            moveSelection(1)
        } else {
            moveControlSelection(1)
        }
    }

    override fun onNavUp() {
        if (isTransitioningMode || dismissAfterLastFlyOut) return
        finishEntranceAnimation()
        if (isControlModalOpen) {
            moveBluetoothSelection(-1)
            return
        }
        val nextMode = if (currentMode == OverlayMode.APP_SWITCHER) {
            OverlayMode.CONTROL_CENTER
        } else {
            OverlayMode.APP_SWITCHER
        }
        switchOverlayMode(nextMode, slideUp = false)
    }

    override fun onNavDown() {
        if (isTransitioningMode || dismissAfterLastFlyOut) return
        finishEntranceAnimation()
        if (isControlModalOpen) {
            moveBluetoothSelection(1)
            return
        }
        val nextMode = if (currentMode == OverlayMode.APP_SWITCHER) {
            OverlayMode.CONTROL_CENTER
        } else {
            OverlayMode.APP_SWITCHER
        }
        switchOverlayMode(nextMode, slideUp = true)
    }

    override fun onNavStart() {
        if (dismissAfterLastFlyOut) return
        if (isControlModalOpen) {
            if (bluetoothDeviceAdapter.itemCount > 0 && bluetoothDeviceAdapter.selectedPosition != 0) {
                triggerHaptic()
                bluetoothDeviceAdapter.selectedPosition = 0
                binding.rvBluetoothDevices.scrollToPosition(0)
            }
            return
        }
        if (currentMode == OverlayMode.APP_SWITCHER) {
            if (tasks.isEmpty()) return
            val current = pendingTarget ?: taskAdapter.selectedPosition
            if (current != 0) {
                smoothScrollToCenter(0, isJumpFast = true)
            }
        } else {
            if (controlCards.isEmpty()) return
            val current = controlPendingTarget ?: controlAdapter.selectedPosition
            if (current != 0) {
                smoothScrollControlToCenter(0, isJumpFast = true)
            }
        }
    }

    override fun onNavEnd() {
        if (dismissAfterLastFlyOut) return
        if (isControlModalOpen) {
            val end = bluetoothDeviceAdapter.itemCount - 1
            if (end >= 0 && bluetoothDeviceAdapter.selectedPosition != end) {
                triggerHaptic()
                bluetoothDeviceAdapter.selectedPosition = end
                binding.rvBluetoothDevices.scrollToPosition(end)
            }
            return
        }
        if (currentMode == OverlayMode.APP_SWITCHER) {
            if (tasks.isEmpty()) return
            val end = tasks.size - 1
            val current = pendingTarget ?: taskAdapter.selectedPosition
            if (current != end) {
                smoothScrollToCenter(end, isJumpFast = true)
            }
        } else {
            if (controlCards.isEmpty()) return
            val end = controlCards.size - 1
            val current = controlPendingTarget ?: controlAdapter.selectedPosition
            if (current != end) {
                smoothScrollControlToCenter(end, isJumpFast = true)
            }
        }
    }

    override fun onVolumeDown() {
        adjustVolume(AudioManager.ADJUST_LOWER)
    }

    override fun onVolumeUp() {
        adjustVolume(AudioManager.ADJUST_RAISE)
    }

    override fun onBrightnessDown() {
        changeBrightness(-BRIGHTNESS_STEP)
    }

    override fun onBrightnessUp() {
        changeBrightness(BRIGHTNESS_STEP)
    }

    override fun onButtonSelect() {
        if (isTransitioningMode) return
        finishEntranceAnimation()
        if (isBluetoothMenuOpen) {
            handleSelectedBluetoothDeviceAction()
            return
        }
        if (isWifiMenuOpen) {
            handleSelectedWifiNetworkAction()
            return
        }
        if (isHandheldModalOpen) {
            handleSelectedHandheldModalAction()
            return
        }
        if (currentMode == OverlayMode.APP_SWITCHER) {
            switchSelectedTask()
        } else {
            openSelectedControlMenu()
        }
    }

    override fun onButtonBack() {
        if (isTransitioningMode) return
        finishEntranceAnimation()
        if (isControlModalOpen) {
            closeControlModal()
            return
        }
        dismissSwitcher()
    }

    override fun onButtonAction() {
        if (isTransitioningMode) return
        finishEntranceAnimation()
        if (isBluetoothMenuOpen) {
            toggleBluetoothFromModal()
            return
        }
        if (isWifiMenuOpen) {
            toggleWifiFromModal()
            return
        }
        if (isHandheldModalOpen) {
            cycleHandheldModalOption()
            return
        }
        if (currentMode == OverlayMode.APP_SWITCHER) {
            killSelectedTask()
        } else {
            toggleSelectedControlCard()
        }
    }

    override fun onButtonY() {
        if (isTransitioningMode) return
        finishEntranceAnimation()
        toggleBackgroundMedia()
    }

}
