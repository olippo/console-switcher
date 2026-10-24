package com.example.consoleswitcher3.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.model.DeckTask

class DeckTaskAdapter(
    private var tasks: List<DeckTask>,
    private val onTaskClick: (Int, DeckTask) -> Unit,
    /** Total gap between adjacent cards in px. Negative = keep layout XML padding. */
    private var cardGapPx: Int = -1,
    /** Scale applied to every non-selected card. Selected card is always 1.0f. */
    var sideScale: Float = com.example.consoleswitcher3.prefs.OverlayPrefs.DEFAULT_SIDE_SCALE
) : RecyclerView.Adapter<DeckTaskAdapter.TaskViewHolder>() {

    var selectedPosition: Int = 0
        set(value) {
            field = value
        }

    companion object {
        private const val PAYLOAD_PREVIEW = "preview"
    }

    fun updateTasks(newTasks: List<DeckTask>) {
        // Own a copy: the activity mutates its list in place (clear/addAll)
        // and aliasing it would corrupt diffs and double-apply removals.
        this.tasks = newTasks.toList()
        if (selectedPosition >= tasks.size) {
            selectedPosition = (tasks.size - 1).coerceAtLeast(0)
        }
        notifyDataSetChanged()
    }

    /**
     * Rebinds only rows whose preview bitmap arrived after first layout
     * (second-pass snapshot refresh). A full notifyDataSetChanged() here
     * would visibly blink the whole strip; targeted updates don't.
     */
    fun updatePreviewsIfGained(newTasks: List<DeckTask>) {
        if (newTasks.size != tasks.size) {
            updateTasks(newTasks)
            return
        }
        val oldTasks = tasks
        this.tasks = newTasks.toList()
        if (selectedPosition >= tasks.size) {
            selectedPosition = (tasks.size - 1).coerceAtLeast(0)
        }
        for (i in tasks.indices) {
            // refreshMissingPreviews copies only rows that gained a bitmap;
            // untouched rows keep their instance: rebind just the new ones.
            val old = oldTasks.getOrNull(i)
            val new = newTasks[i]
            if (new.isCurrentTask) continue
            if (new !== old && new.previewBitmap != null && old?.previewBitmap == null) {
                notifyItemChanged(i, PAYLOAD_PREVIEW)
            }
        }
    }

    fun removeAt(position: Int) {
        if (position !in tasks.indices) return
        this.tasks = this.tasks.toMutableList().also { it.removeAt(position) }
        if (selectedPosition >= tasks.size) {
            selectedPosition = (tasks.size - 1).coerceAtLeast(0)
        }
        notifyItemRemoved(position)
    }

    fun getSelectedTask(): DeckTask? {
        return if (selectedPosition in tasks.indices) tasks[selectedPosition] else null
    }

    fun getTaskAt(position: Int): DeckTask? {
        return if (position in tasks.indices) tasks[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deck_task, parent, false)
        if (cardGapPx >= 0) {
            val half = cardGapPx / 2
            view.setPadding(half, view.paddingTop, half, view.paddingBottom)
        }
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(tasks[position], position == selectedPosition)
    }

    override fun onBindViewHolder(
        holder: TaskViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_PREVIEW)) {
            holder.bindPreviewOnly(tasks[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun getItemCount(): Int = tasks.size

    inner class TaskViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {

        private val cardContainer =
            itemView.findViewById(R.id.cardContainer) as? androidx.cardview.widget.CardView
        private val cardInnerLayout = itemView.findViewById(R.id.cardInnerLayout) as? android.view.View
        private val borderOverlay = itemView.findViewById(R.id.vBorder) as? android.view.View
        private val ivTaskPreview = itemView.findViewById(R.id.ivTaskPreview) as android.widget.ImageView
        private val ivCurrentTaskArrow = itemView.findViewById(R.id.ivCurrentTaskArrow) as? android.widget.ImageView
        private val labelRow = itemView.findViewById(R.id.labelRow) as? android.view.View
        private val ivAppIcon = itemView.findViewById(R.id.ivAppIcon) as? android.widget.ImageView
        private val tvAppTitle = itemView.findViewById(R.id.tvAppTitle) as? android.widget.TextView

        /** Last focus state applied by setCenterFocused; guards per-frame
         *  redundant background swaps (each set invalidates + flickers). */
        private var lastFocused: Boolean? = null

        private var currentTask: DeckTask? = null

        fun bind(task: DeckTask, isSelected: Boolean) {
            currentTask = task
            // Recycled views keep the last transform. Reset to the discrete
            // rest state immediately so a former center card doesn't flash
            // large when reused far out (per-frame scroll listener refines it).
            itemView.pivotX = (itemView.width / 2f).takeIf { it > 0f } ?: itemView.pivotX
            val restScale = if (isSelected) 1.0f else sideScale
            itemView.scaleX = restScale
            itemView.scaleY = restScale
            // translationX is written per-frame by the overlay's XMB transform
            // (gap compensation). It MUST be reset here: a recycled view keeps
            // its old offset, and if it re-enters without a scroll delta no
            // onScrolled fires to correct it — the card renders shifted
            // off-screen ("disappeared") until the next scroll fixes it.
            itemView.translationX = 0f
            itemView.translationY = 0f
            if (itemView.alpha != 1f) itemView.alpha = 1f
            itemView.translationZ = if (isSelected) 20f else 0f
            // App preview snapshot, with app-icon fallback when the system
            // hasn't cached a snapshot yet (e.g. the task that was foreground
            // when the overlay opened).
            ivTaskPreview.setBackgroundColor(
                com.example.consoleswitcher3.prefs.OverlayPrefs.getPreviewBg(itemView.context)
            )
            // Keep the CardView shell in sync with the fill so corners match.
            cardContainer?.setCardBackgroundColor(
                if (task.isCurrentTask) {
                    0xFF1D1D1D.toInt()
                } else if (isSelected) {
                    com.example.consoleswitcher3.prefs.OverlayPrefs.getCardFocusedBg(itemView.context)
                } else {
                    com.example.consoleswitcher3.prefs.OverlayPrefs.getCardBg(itemView.context)
                }
            )
            bindPreviewOnly(task)

            // Force the focus visuals to refresh for this (possibly recycled) row.
            lastFocused = null
            setCenterFocused(isSelected, animate = false)

            // App identity row under the card (icon + title, set once per bind).
            if (task.icon != null) {
                ivAppIcon?.setImageDrawable(task.icon.constantState?.newDrawable(itemView.resources) ?: task.icon)
            } else {
                ivAppIcon?.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            tvAppTitle?.text = task.label

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    getTaskAt(pos)?.let { onTaskClick(pos, it) }
                }
            }
        }

        /** Second-pass snapshot arrival: swap the image only, no transform reset. */
        fun bindPreviewOnly(task: DeckTask) {
            currentTask = task
            if (task.isCurrentTask) {
                ivTaskPreview.visibility = View.GONE
                ivCurrentTaskArrow?.visibility = View.VISIBLE
                updateArrowColor(lastFocused ?: (bindingAdapterPosition == selectedPosition))
                return
            }

            ivCurrentTaskArrow?.visibility = View.GONE
            ivTaskPreview.visibility = View.VISIBLE

            if (task.previewBitmap != null) {
                ivTaskPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                ivTaskPreview.setPadding(0, 0, 0, 0)
                ivTaskPreview.setImageBitmap(task.previewBitmap)
            } else if (task.icon != null) {
                ivTaskPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val pad = (48 * itemView.resources.displayMetrics.density).toInt()
                ivTaskPreview.setPadding(pad, pad, pad, pad)
                ivTaskPreview.setImageDrawable(task.icon.constantState?.newDrawable(itemView.resources) ?: task.icon)
            } else {
                ivTaskPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                ivTaskPreview.setPadding(0, 0, 0, 0)
                ivTaskPreview.setImageResource(android.R.drawable.ic_menu_compass)
            }
        }

        private fun updateArrowColor(isFocused: Boolean) {
            if (ivCurrentTaskArrow?.visibility != View.VISIBLE) return
            val context = itemView.context
            val color = androidx.core.content.ContextCompat.getColor(context, R.color.deck_text_primary)
            ivCurrentTaskArrow?.setColorFilter(color)
        }

        fun setCenterFocused(isFocused: Boolean, animate: Boolean = true) {
            if (lastFocused == isFocused) return
            lastFocused = isFocused
            updateArrowColor(isFocused)
            // Ensure CardView shell background matches currentTask or focus state
            if (currentTask?.isCurrentTask == true) {
                cardContainer?.setCardBackgroundColor(0xFF1D1D1D.toInt())
            } else {
                cardContainer?.setCardBackgroundColor(
                    if (isFocused) {
                        com.example.consoleswitcher3.prefs.OverlayPrefs.getCardFocusedBg(itemView.context)
                    } else {
                        com.example.consoleswitcher3.prefs.OverlayPrefs.getCardBg(itemView.context)
                    }
                )
            }
            // Fill behind the preview (visible in icon-fallback mode).
            cardInnerLayout?.background = getFillDrawable(isFocused)
            // Stroke overlay ON TOP of the preview — the old background-based
            // border was hidden behind opaque snapshots (background draws
            // behind children), which is why the setting appeared to do nothing.
            borderOverlay?.background = getStrokeDrawable(isFocused)

            // App icon + title under the card: smoothly fade in when focused, fade out when unfocused.
            // INVISIBLE keeps the row's height reserved so toggling never
            // re-lays out the strip mid-scroll (GONE caused jumps/flicker).
            labelRow?.animate()?.cancel()
            if (isFocused) {
                labelRow?.visibility = View.VISIBLE
                if (animate) {
                    labelRow?.animate()
                        ?.alpha(1f)
                        ?.setDuration(160L)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.withLayer()
                        ?.start()
                } else {
                    labelRow?.alpha = 1f
                }
            } else {
                if (animate && labelRow?.visibility == View.VISIBLE && (labelRow?.alpha ?: 0f) > 0f) {
                    labelRow?.animate()
                        ?.alpha(0f)
                        ?.setDuration(120L)
                        ?.setInterpolator(AccelerateInterpolator())
                        ?.withLayer()
                        ?.withEndAction {
                            if (lastFocused == false) {
                                labelRow?.visibility = View.INVISIBLE
                            }
                        }
                        ?.start()
                } else {
                    labelRow?.alpha = 0f
                    labelRow?.visibility = View.INVISIBLE
                }
            }
        }

        private var cachedBorderColor: Int = Int.MIN_VALUE
        private var cachedBorderWidthDp: Float = Float.MIN_VALUE
        private var cachedCardBorder: Int = Int.MIN_VALUE
        private var cachedCardBgForStroke: Int = Int.MIN_VALUE
        private var cachedFocusedBgForStroke: Int = Int.MIN_VALUE
        private var cachedFillFocusedColor: Int = Int.MIN_VALUE
        private var cachedFillNormalColor: Int = Int.MIN_VALUE
        private var cachedStrokeFocused: android.graphics.drawable.GradientDrawable? = null
        private var cachedStrokeNormal: android.graphics.drawable.GradientDrawable? = null
        private var cachedFillFocused: android.graphics.drawable.GradientDrawable? = null
        private var cachedFillNormal: android.graphics.drawable.GradientDrawable? = null
        private var cachedFillCurrentTask: android.graphics.drawable.GradientDrawable? = null

        private fun getFillDrawable(isFocused: Boolean): android.graphics.drawable.Drawable {
            if (currentTask?.isCurrentTask == true) {
                val fill = 0xFF1D1D1D.toInt()
                if (cachedFillCurrentTask == null) {
                    cachedFillCurrentTask = buildFillDrawable(fill)
                }
                return cachedFillCurrentTask!!
            }
            val context = itemView.context
            return if (isFocused) {
                val fill = com.example.consoleswitcher3.prefs.OverlayPrefs.getCardFocusedBg(context)
                if (cachedFillFocused == null || fill != cachedFillFocusedColor) {
                    cachedFillFocusedColor = fill
                    cachedFillFocused = buildFillDrawable(fill)
                }
                cachedFillFocused!!
            } else {
                val fill = com.example.consoleswitcher3.prefs.OverlayPrefs.getCardBg(context)
                if (cachedFillNormal == null || fill != cachedFillNormalColor) {
                    cachedFillNormalColor = fill
                    cachedFillNormal = buildFillDrawable(fill)
                }
                cachedFillNormal!!
            }
        }

        private fun getStrokeDrawable(isFocused: Boolean): android.graphics.drawable.Drawable {
            val context = itemView.context
            val borderColor = com.example.consoleswitcher3.prefs.OverlayPrefs.getBorderColor(context)
            val borderWidthDp = com.example.consoleswitcher3.prefs.OverlayPrefs.getBorderWidthDp(context)
            val cardBorder = com.example.consoleswitcher3.prefs.OverlayPrefs.getCardBorder(context)
            val cardBg = com.example.consoleswitcher3.prefs.OverlayPrefs.getCardBg(context)
            val cardFocusedBg = com.example.consoleswitcher3.prefs.OverlayPrefs.getCardFocusedBg(context)
            if (borderColor != cachedBorderColor || borderWidthDp != cachedBorderWidthDp ||
                cardBorder != cachedCardBorder || cardBg != cachedCardBgForStroke ||
                cardFocusedBg != cachedFocusedBgForStroke
            ) {
                cachedBorderColor = borderColor
                cachedBorderWidthDp = borderWidthDp
                cachedCardBorder = cardBorder
                cachedCardBgForStroke = cardBg
                cachedFocusedBgForStroke = cardFocusedBg
                cachedStrokeFocused = null
                cachedStrokeNormal = null
                cachedFillFocused = null
                cachedFillNormal = null
            }
            return if (isFocused) {
                cachedStrokeFocused ?: buildStrokeDrawable(
                    strokeColor = borderColor,
                    strokeWidthDp = borderWidthDp
                ).also { cachedStrokeFocused = it }
            } else {
                cachedStrokeNormal ?: buildStrokeDrawable(
                    strokeColor = cardBorder,
                    strokeWidthDp = borderWidthDp
                ).also { cachedStrokeNormal = it }
            }
        }

        private fun buildFillDrawable(fillColor: Int): android.graphics.drawable.GradientDrawable {
            val density = itemView.resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(fillColor)
            }
        }

        private fun buildStrokeDrawable(strokeColor: Int, strokeWidthDp: Float): android.graphics.drawable.GradientDrawable {
            val density = itemView.resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(android.graphics.Color.TRANSPARENT)
                val strokePx = (strokeWidthDp * density).toInt().coerceAtLeast(0)
                setStroke(strokePx, strokeColor)
            }
        }
    }
}
