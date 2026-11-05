package com.example.consoleswitcher3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.controller.GamepadInputHelper
import com.example.consoleswitcher3.databinding.ActivityOverlayBinding
import com.example.consoleswitcher3.model.ControlCard
import com.example.consoleswitcher3.model.DeckTask
import com.example.consoleswitcher3.shizuku.ShizukuManager
import com.example.consoleswitcher3.task.TaskManager
import com.example.consoleswitcher3.ui.DeckControlAdapter
import com.example.consoleswitcher3.ui.DeckTaskAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class OverlayActivity : AppCompatActivity(), GamepadInputHelper.GamepadListener {

    private enum class OverlayMode {
        APP_SWITCHER,
        CONTROL_CENTER
    }

    private lateinit var binding: ActivityOverlayBinding
    private lateinit var gamepadHelper: GamepadInputHelper
    private lateinit var taskAdapter: DeckTaskAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var controlAdapter: DeckControlAdapter
    private lateinit var controlLayoutManager: LinearLayoutManager

    private var currentMode: OverlayMode = OverlayMode.APP_SWITCHER
    private var isTransitioningMode: Boolean = false

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

    private var animatingVictimView: View? = null
    private var removalShiftX: Float = 0f
    private var removalShiftStartIndex: Int = -1
    private var removalLeftShiftX: Float = 0f
    private var removalLeftShiftEndIndex: Int = -1
    private var pendingTargetIndexAfterRemove: Int = -1
    private var slideAnimator: ValueAnimator? = null
    private var pendingSlideRunnable: Runnable? = null
    private var modeTransitionAnimator: ValueAnimator? = null

    private lateinit var audioManager: AudioManager
    private var currentBrightness: Int = 128
    private val indicatorHandler = Handler(Looper.getMainLooper())
    private var lastReportedVolume: Int = -1

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
    }

    private data class PendingToggle(
        val targetState: Boolean,
        val timestamp: Long
    )
    private val pendingToggleStates = mutableMapOf<String, PendingToggle>()

    private val controlCenterReceiver = object : BroadcastReceiver() {
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
                    refreshControlCards()
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
                    refreshControlCards()
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    pendingToggleStates.remove("dnd")
                    refreshControlCards()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    pendingToggleStates.remove("battery_saver")
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
                // When an item is removed, RecyclerView may anchor to the first
                // visible child and clamp excess scroll at the strip boundaries,
                // shifting surviving cards away from exact center. Compensate
                // synchronously inside the layout pass so the target card is
                // locked to rvTasks.width / 2f before any frame is drawn.
                val targetIdx = pendingTargetIndexAfterRemove
                if (targetIdx >= 0) {
                    pendingTargetIndexAfterRemove = -1
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
                if (removalShiftStartIndex >= 0 || removalShiftX != 0f ||
                    removalLeftShiftEndIndex >= 0 || removalLeftShiftX != 0f
                ) {
                    removalShiftStartIndex = -1
                    removalShiftX = 0f
                    removalLeftShiftEndIndex = -1
                    removalLeftShiftX = 0f
                }
                updateXmbTransforms()
            }
        }
        binding.rvTasks.layoutManager = layoutManager

        // Disable default item animations to prevent weird fade/blinking effects on navigation
        binding.rvTasks.itemAnimator = null
        binding.rvTasks.overScrollMode = View.OVER_SCROLL_NEVER

        sideScale = com.example.consoleswitcher3.prefs.OverlayPrefs.getSideScale(this)
        taskAdapter = DeckTaskAdapter(
            tasks = tasks,
            cardWidthPx = getCardWidthPx(),
            cardHeightPx = getCardHeightPx(),
            cardGapPx = getCardGapPx(),
            sideScale = sideScale,
            onTaskClick = { clickedPosition, task ->
                if (!killInFlight) {
                    val current = pendingTarget ?: taskAdapter.selectedPosition
                    if (clickedPosition == current) {
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
                    if (view.width > 0 && view.right > view.left) {
                        applyXmbTransform(view)
                    } else {
                        view.post {
                            if (view.parent != null) applyXmbTransform(view)
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
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
                    killInFlight = false
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
            if (child === animatingVictimView) continue
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
        val rvWidth = binding.rvTasks.width
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
        if (child === animatingVictimView) return
        if (child.right <= child.left || child.height <= 0) return
        val centerX = binding.rvTasks.width / 2f
        if (centerX <= 0f) return

        val pos = layoutManager.getPosition(child)
        val shift = when {
            pos == RecyclerView.NO_POSITION -> 0f
            removalShiftStartIndex >= 0 && pos >= removalShiftStartIndex -> removalShiftX
            removalLeftShiftEndIndex >= 0 && pos <= removalLeftShiftEndIndex -> removalLeftShiftX
            else -> 0f
        }

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

            // Full opacity for every card â€” focus is shown by size/border only
            if (child.alpha != 1f) child.alpha = 1f

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
                if (!isTransitioningMode) {
                    val current = controlPendingTarget ?: controlAdapter.selectedPosition
                    if (clickedPosition == current) {
                        toggleControlCardAt(clickedPosition)
                    } else {
                        smoothScrollControlToCenter(clickedPosition)
                    }
                }
            }
        )
        binding.rvControlCenter.adapter = controlAdapter

        updateControlCenterPadding()

        binding.rvControlCenter.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (view.width > 0 && view.right > view.left) {
                        applyControlXmbTransform(view)
                    } else {
                        view.post {
                            if (view.parent != null) applyControlXmbTransform(view)
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )

        binding.rvControlCenter.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateControlXmbTransforms()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
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
        if (controlCards.size != updated.size) {
            controlCards.clear()
            controlCards.addAll(updated)
            controlAdapter.updateCards(controlCards)
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
        val wifiEnabled = isWifiEnabled()
        val btEnabled = isBluetoothEnabled()
        val dndEnabled = isDndEnabled()
        val darkEnabled = isDarkModeEnabled()
        val rotationEnabled = isRotationAutoEnabled()
        val batterySaverEnabled = isBatterySaverEnabled()
        val nightLightEnabled = isNightLightEnabled()

        return listOf(
            buildControlCardForState("wifi", wifiEnabled),
            buildControlCardForState("bluetooth", btEnabled),
            buildControlCardForState("dnd", dndEnabled),
            buildControlCardForState("dark_mode", darkEnabled),
            buildControlCardForState("rotation", rotationEnabled),
            buildControlCardForState("battery_saver", batterySaverEnabled),
            buildControlCardForState("night_light", nightLightEnabled)
        )
    }

    private fun buildControlCardForState(id: String, enabled: Boolean): ControlCard {
        return when (id) {
            "wifi" -> ControlCard(
                id = "wifi",
                title = "Wi-Fi",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Wireless Enabled" else "Disabled",
                iconRes = if (enabled) R.drawable.ic_wifi_rounded else R.drawable.ic_wifi_off_rounded,
                cardIconRes = if (enabled) R.drawable.ic_wifi_rounded else R.drawable.ic_wifi_off_rounded,
                isEnabled = enabled
            )
            "bluetooth" -> ControlCard(
                id = "bluetooth",
                title = "Bluetooth",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Discoverable & Active" else "Disabled",
                iconRes = if (enabled) R.drawable.ic_bluetooth_rounded else R.drawable.ic_bluetooth_disabled_rounded,
                cardIconRes = if (enabled) R.drawable.ic_bluetooth_rounded else R.drawable.ic_bluetooth_disabled_rounded,
                isEnabled = enabled
            )
            "dnd" -> ControlCard(
                id = "dnd",
                title = "Do Not Disturb",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Notifications Silenced" else "Alerts Allowed",
                iconRes = if (enabled) R.drawable.ic_dnd_rounded else R.drawable.ic_dnd_off_rounded,
                cardIconRes = if (enabled) R.drawable.ic_dnd_rounded else R.drawable.ic_dnd_off_rounded,
                isEnabled = enabled
            )
            "dark_mode" -> ControlCard(
                id = "dark_mode",
                title = "Dark Mode",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Dark Theme Active" else "Light Theme Active",
                iconRes = R.drawable.ic_contrast_rounded,
                cardIconRes = R.drawable.ic_contrast_rounded,
                isEnabled = enabled
            )
            "rotation" -> ControlCard(
                id = "rotation",
                title = "Auto-Rotate",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Auto-Rotation Active" else "Rotation Locked",
                iconRes = if (enabled) R.drawable.ic_screen_rotation_rounded else R.drawable.ic_screen_lock_rotation_rounded,
                cardIconRes = if (enabled) R.drawable.ic_screen_rotation_rounded else R.drawable.ic_screen_lock_rotation_rounded,
                isEnabled = enabled
            )
            "battery_saver" -> ControlCard(
                id = "battery_saver",
                title = "Battery Saver",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Power Saving Active" else "Standard Power",
                iconRes = if (enabled) R.drawable.ic_battery_saver_rounded else R.drawable.ic_battery_full_rounded,
                cardIconRes = if (enabled) R.drawable.ic_battery_saver_rounded else R.drawable.ic_battery_full_rounded,
                isEnabled = enabled
            )
            "night_light" -> ControlCard(
                id = "night_light",
                title = "Night Light",
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = if (enabled) "Warm Tint Active" else "Standard Display",
                iconRes = if (enabled) R.drawable.ic_nightlight_fill_rounded else R.drawable.ic_nightlight_rounded,
                cardIconRes = if (enabled) R.drawable.ic_nightlight_fill_rounded else R.drawable.ic_nightlight_rounded,
                isEnabled = enabled
            )
            else -> ControlCard(
                id = id,
                title = id,
                statusPrimary = if (enabled) "On" else "Off",
                statusSecondary = "",
                iconRes = R.drawable.ic_wifi_rounded,
                cardIconRes = R.drawable.ic_wifi_rounded,
                isEnabled = enabled
            )
        }
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

    private fun toggleSelectedControlCard() {
        if (isTransitioningMode || controlCards.isEmpty()) return
        val pos = findControlCenterPosition()
            ?.takeIf { it in 0 until controlAdapter.itemCount }
            ?: (controlPendingTarget ?: controlAdapter.selectedPosition)
                .takeIf { it in 0 until controlAdapter.itemCount }
            ?: return
        toggleControlCardAt(pos)
    }

    private fun toggleControlCardAt(position: Int) {
        if (position !in controlCards.indices) return
        val currentCard = controlCards[position]
        val targetState = !currentCard.isEnabled
        triggerHaptic()

        // Set pending toggle lock to prevent intermediate hardware states from reverting the UI
        pendingToggleStates[currentCard.id] = PendingToggle(targetState, System.currentTimeMillis())

        // Optimistically update UI immediately
        val optimisticCard = buildControlCardForState(currentCard.id, targetState)
        controlCards[position] = optimisticCard
        controlAdapter.updateCardAt(position, optimisticCard)

        // Animate a subtle press pulse on the card
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
            else -> false
        }
    }

    private fun applyControlFallbackOnMain(id: String, enable: Boolean) {
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

        if (child.alpha != 1f) child.alpha = 1f

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
        binding.rvControlCenter.stopScroll()
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
        if (isTransitioningMode || controlCards.isEmpty()) return
        val base = controlPendingTarget ?: controlAdapter.selectedPosition
        val newPos = (base + delta).coerceIn(0, controlCards.size - 1)
        if (newPos != base) {
            smoothScrollControlToCenter(newPos)
        }
    }

    private fun switchOverlayMode(targetMode: OverlayMode, slideUp: Boolean) {
        if (currentMode == targetMode || isTransitioningMode || killInFlight) return
        if (binding.loadingLayout.visibility == View.VISIBLE) return

        if (targetMode == OverlayMode.CONTROL_CENTER) {
            refreshControlCards()
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

        // Cancel any active scrolls before transitioning
        pendingTarget = null
        programmaticScroll = false
        controlPendingTarget = null
        controlProgrammaticScroll = false
        binding.rvTasks.stopScroll()
        binding.rvControlCenter.stopScroll()

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        modeTransitionAnimator?.cancel()
        modeTransitionAnimator = null

        val hiddenVisibility = if (outgoingView === binding.emptyLayout) View.GONE else View.INVISIBLE

        if (prefs.isCloseAnimOff(closeSpeed)) {
            outgoingView.visibility = hiddenVisibility
            outgoingView.translationY = 0f
            outgoingView.alpha = 1f

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
        val incomingStartY = if (slideUp) flyDistance else -flyDistance

        if (targetMode == OverlayMode.CONTROL_CENTER) {
            updateControlXmbTransforms()
        } else if (tasks.isNotEmpty()) {
            updateXmbTransforms()
        }

        outgoingView.translationY = 0f
        outgoingView.alpha = 1f
        incomingView.translationY = incomingStartY
        incomingView.alpha = 0f
        incomingView.visibility = View.VISIBLE

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                if (isFinishing || isDestroyed) return@addUpdateListener
                val f = anim.animatedValue as Float
                val fadeProgress = (f * 1.6f).coerceIn(0f, 1f)
                outgoingView.translationY = outgoingTargetY * f
                outgoingView.alpha = 1f - fadeProgress
                incomingView.translationY = incomingStartY * (1f - f)
                incomingView.alpha = fadeProgress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modeTransitionAnimator = null
                    outgoingView.visibility = hiddenVisibility
                    outgoingView.translationY = 0f
                    outgoingView.alpha = 1f

                    incomingView.translationY = 0f
                    incomingView.alpha = 1f
                    currentMode = targetMode
                    isTransitioningMode = false
                    if (targetMode == OverlayMode.CONTROL_CENTER) {
                        updateControlXmbTransforms()
                    } else if (tasks.isNotEmpty()) {
                        updateXmbTransforms()
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    modeTransitionAnimator = null
                    isTransitioningMode = false
                }
            })
        }
        modeTransitionAnimator = animator
        animator.start()
    }

    private fun setupListeners() {
        // Tap outside to dismiss
        binding.overlayRoot.setOnClickListener {
            if (killInFlight || isTransitioningMode) return@setOnClickListener
            dismissSwitcher()
        }

        binding.emptyLayout.setOnClickListener {
            openSettings()
        }
    }

    private fun loadTasks() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.emptyLayout.visibility = View.GONE
        binding.rvTasks.visibility = View.INVISIBLE

        lifecycleScope.launch {
            val queriedTasks = TaskManager.getRecentTasks(this@OverlayActivity)
            tasks.clear()
            tasks.addAll(queriedTasks)

            binding.loadingLayout.visibility = View.GONE

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
                binding.rvTasks.visibility = if (currentMode == OverlayMode.APP_SWITCHER) View.VISIBLE else View.INVISIBLE

                taskAdapter.updateTasks(tasks)

                taskAdapter.selectedPosition = 0
                pendingTarget = null
                programmaticScroll = false
                binding.rvTasks.post {
                    updateCenterPadding()
                    // Jump, don't animate, to the first card on open: a smooth
                    // scroll from an unlaid-out position sweeps the whole strip.
                    jumpToCenter(0)
                }
            }

            // Second pass: the app that was foreground when the overlay opened
            // often has no cached snapshot on first query. Retry once after the
            // system has had time to snapshot it in the background. Only the
            // rows that gained a bitmap rebind (no full-strip blink).
            if (queriedTasks.any { it.previewBitmap == null && it.taskId > 0 && !it.isCurrentTask }) {
                val refreshed = TaskManager.refreshMissingPreviews(queriedTasks)
                if (refreshed.any { it.previewBitmap != null }) {
                    taskAdapter.updatePreviewsIfGained(refreshed)
                    tasks.clear()
                    tasks.addAll(refreshed)
                    binding.rvTasks.post { updateXmbTransforms() }
                }
            }
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
        layoutManager.startSmoothScroll(scroller)
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
        binding.rvTasks.stopScroll()
        taskAdapter.selectedPosition = position

        val rvW = binding.rvTasks.width
        if (rvW <= 0) {
            layoutManager.scrollToPosition(position)
            binding.rvTasks.post { updateXmbTransforms() }
            return
        }

        val child = layoutManager.findViewByPosition(position)
        if (child != null && child.width > 0 && child.right > child.left) {
            val childCenter = (child.left + child.right) / 2f
            val centerX = rvW / 2f
            val dx = Math.round(childCenter - centerX)
            if (dx != 0) {
                binding.rvTasks.scrollBy(dx, 0)
            }
            updateXmbTransforms()
        } else {
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
        if (killInFlight || tasks.isEmpty()) return
        // Chain off the in-flight target so rapid presses walk the strip
        // instead of re-issuing the same step from the last landed index.
        val base = pendingTarget ?: taskAdapter.selectedPosition
        val newPos = (base + delta).coerceIn(0, tasks.size - 1)
        if (newPos != base) {
            smoothScrollToCenter(newPos)
        }
    }

    private fun switchTask(task: DeckTask) {
        lifecycleScope.launch {
            triggerHaptic()
            TaskManager.switchTask(this@OverlayActivity, task)
            dismissSwitcher()
        }
    }

    private fun switchSelectedTask() {
        if (killInFlight) return
        val task = taskAdapter.getSelectedTask() ?: return
        switchTask(task)
    }

    /** Guards overlapping kills and actions during animated removal. */
    private var killInFlight = false

    private fun killSelectedTask() {
        if (killInFlight) return
        // Kill what is VISUALLY centered (border + label + full size are all
        // distance-driven, so the strip center is the selection truth).
        val victimPos = findCenterPosition()
            ?.takeIf { it in 0 until taskAdapter.itemCount }
            ?: (pendingTarget ?: taskAdapter.selectedPosition)
                .takeIf { it in 0 until taskAdapter.itemCount }
            ?: taskAdapter.selectedPosition.coerceIn(0, (taskAdapter.itemCount - 1).coerceAtLeast(0))
                .takeIf { taskAdapter.itemCount > 0 }
            ?: return
        val task = taskAdapter.getTaskAt(victimPos) ?: return
        android.util.Log.d(TAG, "kill press: victim=$victimPos selected=${taskAdapter.selectedPosition} pending=$pendingTarget count=${taskAdapter.itemCount}")
        killInFlight = true
        triggerHaptic()

        // Stop any in-flight navigation scroll immediately
        pendingTarget = null
        programmaticScroll = false
        binding.rvTasks.stopScroll()

        // Asynchronously execute kill in background without blocking the UI animation
        lifecycleScope.launch {
            try {
                val success = TaskManager.killTask(this@OverlayActivity, task)
                val msg = if (success) "Force stopped ${task.label}" else "Removed ${task.label}"
                android.util.Log.d(TAG, msg)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "killTask error", e)
            }
        }

        val prefs = com.example.consoleswitcher3.prefs.OverlayPrefs
        val closeSpeed = prefs.getCloseAnimSpeed(this)
        val isOff = prefs.isCloseAnimOff(closeSpeed)
        val victimView = layoutManager.findViewByPosition(victimPos)

        if (isOff || victimView == null) {
            // Instant removal without animation
            if (victimPos in tasks.indices && victimPos < taskAdapter.itemCount) {
                tasks.removeAt(victimPos)
                taskAdapter.removeAt(victimPos)
            }
            if (tasks.isEmpty()) {
                killInFlight = false
                dismissSwitcher()
                return
            }

            val nextPos = victimPos.coerceAtMost(tasks.size - 1)
            taskAdapter.selectedPosition = nextPos

            binding.rvTasks.post {
                if (isFinishing || isDestroyed) {
                    killInFlight = false
                    return@post
                }
                jumpToCenter(nextPos)
                updateXmbTransforms()
                killInFlight = false
            }
            return
        }

        // Dismiss (fly-up) speed follows the App closing slider.
        val closeSpeedMult = (closeSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_CLOSE_SPEED).coerceIn(0.5f, 3.0f)
        val flyDuration = (190L / closeSpeedMult).toLong().coerceIn(50L, 600L)
        // Collapse (slide) speed follows the Carousel navigation slider.
        val navSpeed = prefs.getAnimSpeed(this)
        val navSpeedMult = if (prefs.isAnimOff(navSpeed)) 3.0f
            else (navSpeed / com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_NAV_SPEED).coerceIn(0.5f, 3.0f)
        val slideDuration = (210L / navSpeedMult).toLong().coerceIn(60L, 500L)
        val slideDelayMs = com.example.consoleswitcher3.prefs.OverlayPrefs.getSlideDelayMs(this).toLong()

        animatingVictimView = victimView
        victimView.translationZ = 40f

        val flyDistance = (binding.rvTasks.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat() * 1.5f

        val hasRightNeighbor = victimPos < tasks.size - 1
        val slotWidth = victimView.width.toFloat().takeIf { it > 0f }
            ?: getItemWidthPx().toFloat()

        var flyDone = false
        var slideDone = false
        var finalized = false
        var watchdogRunnable: Runnable? = null

        fun checkAndFinalize() {
            if (finalized) return
            if (!flyDone || !slideDone) return
            finalized = true
            watchdogRunnable?.let { binding.rvTasks.removeCallbacks(it) }

            if (isFinishing || isDestroyed) {
                killInFlight = false
                slideAnimator = null
                animatingVictimView = null
                return
            }

            if (tasks.size <= 1) {
                animatingVictimView = null
                victimView.translationY = 0f
                victimView.alpha = 1f
                victimView.scaleX = 1f
                victimView.scaleY = 1f
                victimView.translationZ = 0f
                tasks.clear()
                taskAdapter.updateTasks(emptyList())
                killInFlight = false
                dismissSwitcher()
                return
            }

            val nextPos = if (hasRightNeighbor) {
                victimPos.coerceAtMost(tasks.size - 2)
            } else {
                victimPos - 1
            }

            // Lock the surviving target card to exact center inside the layout
            // pass triggered by notifyItemRemoved.
            pendingTargetIndexAfterRemove = nextPos

            // Reset victim view and animation shifts before adapter removal so if
            // RecyclerView recycles or detaches it during layout, its view state is clean.
            removalShiftStartIndex = -1
            removalShiftX = 0f
            removalLeftShiftEndIndex = -1
            removalLeftShiftX = 0f
            animatingVictimView = null
            victimView.translationY = 0f
            victimView.alpha = 1f
            victimView.scaleX = 1f
            victimView.scaleY = 1f
            victimView.translationZ = 0f

            if (victimPos in tasks.indices && victimPos < taskAdapter.itemCount) {
                tasks.removeAt(victimPos)
                taskAdapter.removeAt(victimPos)
                taskAdapter.selectedPosition = nextPos
            }

            var cleanedUp = false
            fun finishKill() {
                if (cleanedUp) return
                cleanedUp = true
                if (!isFinishing && !isDestroyed) {
                    updateXmbTransforms()
                    triggerHaptic()
                }
                killInFlight = false
            }
            binding.rvTasks.doOnNextLayout { finishKill() }
            binding.rvTasks.post { finishKill() }
        }

        // Safety watchdog: ensure kill state is never locked permanently if animation callbacks drop
        val watchdogTimeoutMs = (flyDuration + slideDuration + slideDelayMs + 300L).coerceAtLeast(600L)
        val watchdog = Runnable {
            if (!finalized && !isFinishing && !isDestroyed) {
                android.util.Log.w(TAG, "kill watchdog triggered: forcing checkAndFinalize")
                flyDone = true
                slideDone = true
                checkAndFinalize()
            }
        }
        watchdogRunnable = watchdog
        binding.rvTasks.postDelayed(watchdog, watchdogTimeoutMs)

        // Start the slide animation (in parallel with the fly-up or delayed by slideDelayMs).
        val centerX = binding.rvTasks.width / 2f
        if (hasRightNeighbor) {
            // Slide right neighbors to exact center (not just -slotWidth, which would
            // land off-center if kill was pressed while the strip was mid-scroll),
            // and simultaneously settle left neighbors if the strip was slightly off-center.
            val rightView = layoutManager.findViewByPosition(victimPos + 1)
            val rightCenter = if (rightView != null && rightView.width > 0 && rightView.right > rightView.left) {
                (rightView.left + rightView.right) / 2f
            } else {
                centerX + slotWidth
            }
            val targetRightShift = centerX - rightCenter

            val leftView = if (victimPos > 0) layoutManager.findViewByPosition(victimPos - 1) else null
            val targetLeftShift = if (leftView != null && leftView.width > 0 && leftView.right > leftView.left) {
                val leftCenter = (leftView.left + leftView.right) / 2f
                (centerX - slotWidth) - leftCenter
            } else {
                0f
            }

            removalShiftStartIndex = victimPos + 1
            removalShiftX = 0f
            removalLeftShiftEndIndex = victimPos - 1
            removalLeftShiftX = 0f

            pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
            slideAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = slideDuration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    if (isFinishing || isDestroyed) return@addUpdateListener
                    val f = anim.animatedValue as Float
                    removalShiftX = targetRightShift * f
                    removalLeftShiftX = targetLeftShift * f
                    updateXmbTransforms()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        slideAnimator = null
                        slideDone = true
                        checkAndFinalize()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        slideAnimator = null
                        removalShiftStartIndex = -1
                        removalShiftX = 0f
                        removalLeftShiftEndIndex = -1
                        removalLeftShiftX = 0f
                        animatingVictimView = null
                        victimView.translationY = 0f
                        victimView.alpha = 1f
                        victimView.scaleX = 1f
                        victimView.scaleY = 1f
                        victimView.translationZ = 0f
                        killInFlight = false
                    }
                })
            }
            slideAnimator = animator

            val runnable = Runnable {
                pendingSlideRunnable = null
                if (!isFinishing && !isDestroyed) {
                    animator.start()
                }
            }
            pendingSlideRunnable = runnable

            if (slideDelayMs > 0L) {
                binding.rvTasks.postDelayed(runnable, slideDelayMs)
            } else {
                runnable.run()
            }
        } else if (tasks.size > 1) {
            // Victim was the last card: slide left neighbors right so nextPos lands at exact center.
            val nextPos = victimPos - 1
            val nextView = layoutManager.findViewByPosition(nextPos)
            val nextCenter = if (nextView != null && nextView.width > 0 && nextView.right > nextView.left) {
                (nextView.left + nextView.right) / 2f
            } else {
                centerX - slotWidth
            }
            val targetLeftShift = centerX - nextCenter

            removalLeftShiftEndIndex = nextPos
            removalLeftShiftX = 0f

            pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
            slideAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(0f, targetLeftShift).apply {
                duration = slideDuration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    if (isFinishing || isDestroyed) return@addUpdateListener
                    removalLeftShiftX = anim.animatedValue as Float
                    updateXmbTransforms()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        slideAnimator = null
                        slideDone = true
                        checkAndFinalize()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        slideAnimator = null
                        removalLeftShiftEndIndex = -1
                        removalLeftShiftX = 0f
                        animatingVictimView = null
                        victimView.translationY = 0f
                        victimView.alpha = 1f
                        victimView.scaleX = 1f
                        victimView.scaleY = 1f
                        victimView.translationZ = 0f
                        killInFlight = false
                    }
                })
            }
            slideAnimator = animator

            val runnable = Runnable {
                pendingSlideRunnable = null
                if (!isFinishing && !isDestroyed) {
                    animator.start()
                }
            }
            pendingSlideRunnable = runnable

            if (slideDelayMs > 0L) {
                binding.rvTasks.postDelayed(runnable, slideDelayMs)
            } else {
                runnable.run()
            }
        } else {
            // Single card (tasks.size <= 1), no slide needed
            slideDone = true
        }

        victimView.animate()
            .translationY(-flyDistance)
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(flyDuration)
            .setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction {
                flyDone = true
                checkAndFinalize()
            }
            .start()
    }

    private fun dismissSwitcher() {
        killInFlight = false
        pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
        pendingSlideRunnable = null
        slideAnimator?.cancel()
        animatingVictimView?.animate()?.cancel()
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
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
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
        try {
            unregisterReceiver(volumeReceiver)
        } catch (ignored: Throwable) {}
        try {
            unregisterReceiver(controlCenterReceiver)
        } catch (ignored: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        killInFlight = false
        isTransitioningMode = false
        initBrightness()
        lastReportedVolume = getVolumePercent()
        refreshControlCards()
    }

    override fun onPause() {
        super.onPause()
        killInFlight = false
        isTransitioningMode = false
        gamepadHelper.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        killInFlight = false
        isTransitioningMode = false
        gamepadHelper.reset()
        indicatorHandler.removeCallbacksAndMessages(null)
        binding.layoutBrightness.animate().cancel()
        binding.layoutVolume.animate().cancel()
        binding.layoutMedia.animate().cancel()
        modeTransitionAnimator?.cancel()
        modeTransitionAnimator = null
        pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
        pendingSlideRunnable = null
        slideAnimator?.cancel()
        animatingVictimView?.animate()?.cancel()
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
                sendMediaCommand(play = false)
                showMediaIndicator("Paused")
            } else {
                val hasSession = checkHasMediaSession()
                if (hasSession) {
                    // There is an active media session waiting -> explicitly play/resume it
                    sendMediaCommand(play = true)
                    showMediaIndicator("Unpaused")
                } else {
                    // No recognized active session: attempt play and verify if audio starts
                    sendMediaCommand(play = true)
                    withContext(Dispatchers.IO) {
                        delay(150)
                    }
                    if (audioManager.isMusicActive) {
                        showMediaIndicator("Unpaused")
                    } else {
                        showMediaIndicator("No Media")
                    }
                }
            }
        }
    }

    private fun sendMediaCommand(play: Boolean) {
        val hasShizuku = ShizukuManager.hasShizukuPermission()
        if (hasShizuku) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cmd = if (play) "play" else "pause"
                    val res = ShizukuManager.execCommand("cmd", "media_session", "dispatch", cmd)
                    if (!res.isSuccess) {
                        val keyCode = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    }
                } catch (ignored: Throwable) {
                    val keyCode = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }
        } else {
            try {
                val keyCode = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (ignored: Throwable) {}
        }
    }

    private suspend fun checkHasMediaSession(): Boolean = withContext(Dispatchers.IO) {
        if (!ShizukuManager.hasShizukuPermission()) return@withContext false
        try {
            val res = ShizukuManager.execCommand("cmd", "media_session", "list-sessions")
            if (res.isSuccess && res.output.isNotBlank()) {
                val validLines = res.output.lines().filter { line ->
                    line.isNotBlank() &&
                    !line.startsWith("Sessions:") &&
                    !line.contains("com.android.bluetooth")
                }
                if (validLines.isNotEmpty()) return@withContext true
            }

            val dumpRes = ShizukuManager.execCommand("dumpsys", "media_session")
            if (dumpRes.isSuccess && dumpRes.output.isNotBlank()) {
                val out = dumpRes.output
                val hasSession = !out.contains("Media button session is null")
                val hasActive = out.lines().any { it.contains("active=true") && !it.contains("com.android.bluetooth") }
                val hasPlayingOrPaused = out.contains("state=2") || out.contains("state=3")
                if (hasSession || hasActive || hasPlayingOrPaused) {
                    return@withContext true
                }
            }
        } catch (ignored: Throwable) {}
        false
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
            if (killInFlight) return true
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
        if (isTransitioningMode) return
        if (currentMode == OverlayMode.APP_SWITCHER) {
            moveSelection(-1)
        } else {
            moveControlSelection(-1)
        }
    }

    override fun onNavRight() {
        if (isTransitioningMode) return
        if (currentMode == OverlayMode.APP_SWITCHER) {
            moveSelection(1)
        } else {
            moveControlSelection(1)
        }
    }

    override fun onNavUp() {
        if (isTransitioningMode || killInFlight) return
        val nextMode = if (currentMode == OverlayMode.APP_SWITCHER) {
            OverlayMode.CONTROL_CENTER
        } else {
            OverlayMode.APP_SWITCHER
        }
        switchOverlayMode(nextMode, slideUp = false)
    }

    override fun onNavDown() {
        if (isTransitioningMode || killInFlight) return
        val nextMode = if (currentMode == OverlayMode.APP_SWITCHER) {
            OverlayMode.CONTROL_CENTER
        } else {
            OverlayMode.APP_SWITCHER
        }
        switchOverlayMode(nextMode, slideUp = true)
    }

    override fun onNavStart() {
        if (isTransitioningMode || killInFlight) return
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
        if (isTransitioningMode || killInFlight) return
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
        if (currentMode == OverlayMode.APP_SWITCHER) {
            switchSelectedTask()
        } else {
            toggleSelectedControlCard()
        }
    }

    override fun onButtonBack() {
        if (killInFlight || isTransitioningMode) return
        dismissSwitcher()
    }

    override fun onButtonAction() {
        if (isTransitioningMode) return
        if (currentMode == OverlayMode.APP_SWITCHER) {
            killSelectedTask()
        }
    }

    override fun onButtonY() {
        if (killInFlight || isTransitioningMode) return
        toggleBackgroundMedia()
    }

}
