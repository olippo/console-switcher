package com.example.consoleswitcher3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.controller.GamepadInputHelper
import com.example.consoleswitcher3.databinding.ActivityOverlayBinding
import com.example.consoleswitcher3.model.DeckTask
import com.example.consoleswitcher3.shizuku.ShizukuManager
import com.example.consoleswitcher3.task.TaskManager
import com.example.consoleswitcher3.ui.DeckTaskAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayActivity : AppCompatActivity(), GamepadInputHelper.GamepadListener {

    private lateinit var binding: ActivityOverlayBinding
    private lateinit var gamepadHelper: GamepadInputHelper
    private lateinit var taskAdapter: DeckTaskAdapter
    private lateinit var layoutManager: LinearLayoutManager

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

    private var tasks = mutableListOf<DeckTask>()
    private var vibrator: Vibrator? = null
    private var sideScale = com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_SIDE_SCALE

    private var animatingVictimView: View? = null
    private var removalShiftX: Float = 0f
    private var removalShiftStartIndex: Int = -1
    private var slideAnimator: ValueAnimator? = null
    private var pendingSlideRunnable: Runnable? = null

    private lateinit var audioManager: AudioManager
    private var currentBrightness: Int = 128

    companion object {
        private const val TAG = "OverlayActivity"
        private const val BRIGHTNESS_STEP = 13 // ~5% of 255
        private const val MIN_BRIGHTNESS = 10
        private const val MAX_BRIGHTNESS = 255
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

        // Card strip height above the bottom edge comes from Settings.
        val bottomGapPx = (com.example.consoleswitcher3.prefs.OverlayPrefs.getBottomGapDp(this) *
                resources.displayMetrics.density).toInt()
        (binding.rvTasks.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = bottomGapPx
            binding.rvTasks.layoutParams = it
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
        gamepadHelper = GamepadInputHelper(this)

        setupRecyclerView()
        setupListeners()
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
        }
        binding.rvTasks.layoutManager = layoutManager

        // Disable default item animations to prevent weird fade/blinking effects on navigation
        binding.rvTasks.itemAnimator = null
        binding.rvTasks.overScrollMode = View.OVER_SCROLL_NEVER

        sideScale = com.example.consoleswitcher3.prefs.OverlayPrefs.getSideScale(this)
        taskAdapter = DeckTaskAdapter(
            tasks = tasks,
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

    private fun getItemWidthPx(): Int {
        val cardWidth = resources.getDimensionPixelSize(R.dimen.xmb_card_width)
        return cardWidth + getCardGapPx()
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
        val shift = if (removalShiftStartIndex >= 0 && pos >= removalShiftStartIndex && pos != RecyclerView.NO_POSITION) {
            removalShiftX
        } else {
            0f
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

    private fun setupListeners() {
        // Tap outside to dismiss
        binding.overlayRoot.setOnClickListener {
            if (killInFlight) return@setOnClickListener
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
                binding.rvTasks.visibility = View.GONE
                binding.emptyLayout.visibility = View.VISIBLE
            } else {
                binding.emptyLayout.visibility = View.GONE
                binding.rvTasks.visibility = View.VISIBLE

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

        fun checkAndFinalize() {
            if (finalized) return
            if (!flyDone || !slideDone) return
            finalized = true

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

            if (victimPos in tasks.indices && victimPos < taskAdapter.itemCount) {
                tasks.removeAt(victimPos)
                taskAdapter.removeAt(victimPos)
                taskAdapter.selectedPosition = nextPos
            }

            var cleanedUp = false
            fun cleanup() {
                if (cleanedUp) return
                cleanedUp = true
                removalShiftStartIndex = -1
                removalShiftX = 0f
                animatingVictimView = null
                victimView.translationY = 0f
                victimView.alpha = 1f
                victimView.scaleX = 1f
                victimView.scaleY = 1f
                victimView.translationZ = 0f
                if (isFinishing || isDestroyed) {
                    killInFlight = false
                    return
                }
                updateXmbTransforms()
                triggerHaptic()
                killInFlight = false
            }

            binding.rvTasks.doOnNextLayout {
                cleanup()
            }
            binding.rvTasks.post {
                cleanup()
            }
        }

        // Start the slide animation (in parallel with the fly-up or delayed by slideDelayMs).
        if (hasRightNeighbor) {
            removalShiftStartIndex = victimPos + 1
            removalShiftX = 0f

            pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
            slideAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(0f, -slotWidth).apply {
                duration = slideDuration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    if (isFinishing || isDestroyed) return@addUpdateListener
                    removalShiftX = anim.animatedValue as Float
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
            // Victim was the last card: scroll the left neighbor into center in parallel.
            val nextPos = victimPos - 1
            val nextView = layoutManager.findViewByPosition(nextPos)
            val nextCenter = if (nextView != null && nextView.width > 0 && nextView.right > nextView.left) {
                (nextView.left + nextView.right) / 2f
            } else {
                (binding.rvTasks.width / 2f) - slotWidth
            }
            val centerX = binding.rvTasks.width / 2f
            val totalScroll = Math.round(nextCenter - centerX)

            if (totalScroll != 0) {
                var lastScrolled = 0
                pendingSlideRunnable?.let { binding.rvTasks.removeCallbacks(it) }
                slideAnimator?.cancel()
                val animator = ValueAnimator.ofInt(0, totalScroll).apply {
                    duration = slideDuration
                    interpolator = DecelerateInterpolator(1.5f)
                    addUpdateListener { anim ->
                        if (isFinishing || isDestroyed) return@addUpdateListener
                        val current = anim.animatedValue as Int
                        val step = current - lastScrolled
                        lastScrolled = current
                        if (step != 0) {
                            binding.rvTasks.scrollBy(step, 0)
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            slideAnimator = null
                            slideDone = true
                            checkAndFinalize()
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            slideAnimator = null
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
                slideDone = true
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

    override fun onResume() {
        super.onResume()
        initBrightness()
    }

    override fun onPause() {
        super.onPause()
        gamepadHelper.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        gamepadHelper.reset()
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

    private fun adjustVolume(direction: Int) {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
        }
    }

    private fun changeBrightness(delta: Int) {
        currentBrightness = (currentBrightness + delta).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

        // 1. Instantly update current activity window brightness
        val lp = window.attributes
        lp.screenBrightness = currentBrightness / 255.0f
        window.attributes = lp

        // 2. Persist to system setting
        persistSystemBrightness(currentBrightness)
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
        moveSelection(-1)
    }

    override fun onNavRight() {
        moveSelection(1)
    }

    override fun onNavStart() {
        if (killInFlight || tasks.isEmpty()) return
        val current = pendingTarget ?: taskAdapter.selectedPosition
        if (current != 0) {
            smoothScrollToCenter(0, isJumpFast = true)
        }
    }

    override fun onNavEnd() {
        if (killInFlight || tasks.isEmpty()) return
        val end = tasks.size - 1
        val current = pendingTarget ?: taskAdapter.selectedPosition
        if (current != end) {
            smoothScrollToCenter(end, isJumpFast = true)
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
        switchSelectedTask()
    }

    override fun onButtonBack() {
        if (killInFlight) return
        dismissSwitcher()
    }

    override fun onButtonAction() {
        killSelectedTask()
    }

}
