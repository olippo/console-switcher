package com.example.consoleswitcher3.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.model.ControlCard
import com.example.consoleswitcher3.prefs.OverlayPrefs

class DeckControlAdapter(
    private var cards: List<ControlCard>,
    private val onCardClick: (Int, ControlCard) -> Unit,
    private var cardWidthPx: Int = -1,
    private var cardHeightPx: Int = -1,
    private var cardGapPx: Int = -1,
    var sideScale: Float = OverlayPrefs.DEFAULT_SIDE_SCALE
) : RecyclerView.Adapter<DeckControlAdapter.ControlViewHolder>() {

    var selectedPosition: Int = 0

    fun updateCards(newCards: List<ControlCard>) {
        this.cards = newCards.toList()
        if (selectedPosition >= cards.size) {
            selectedPosition = (cards.size - 1).coerceAtLeast(0)
        }
        notifyDataSetChanged()
    }

    fun updateCardAt(position: Int, updatedCard: ControlCard) {
        if (position !in cards.indices) return
        val mutable = cards.toMutableList()
        mutable[position] = updatedCard
        this.cards = mutable
        notifyItemChanged(position, PAYLOAD_STATE_CHANGE)
    }

    fun getSelectedCard(): ControlCard? {
        return if (selectedPosition in cards.indices) cards[selectedPosition] else null
    }

    fun getCardAt(position: Int): ControlCard? {
        return if (position in cards.indices) cards[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ControlViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deck_control, parent, false)
        if (cardWidthPx > 0 && cardHeightPx > 0) {
            val cardContainer = view.findViewById<View>(R.id.cardContainer)
            cardContainer?.layoutParams?.let { params ->
                params.width = cardWidthPx
                params.height = cardHeightPx
                cardContainer.layoutParams = params
            }
            val labelRow = view.findViewById<View>(R.id.labelRow)
            labelRow?.layoutParams?.let { params ->
                params.width = cardWidthPx
                labelRow.layoutParams = params
            }
        }
        if (cardGapPx >= 0) {
            val halfGap = cardGapPx / 2
            view.setPadding(halfGap, view.paddingTop, halfGap, view.paddingBottom)
        }
        return ControlViewHolder(view)
    }

    override fun onBindViewHolder(holder: ControlViewHolder, position: Int) {
        val card = cards[position]
        holder.bind(card, position == selectedPosition)
        val clickAction = { onCardClick(position, cards[position]) }
        holder.itemView.setOnClickListener { clickAction() }
        holder.cardContainer?.setOnClickListener { clickAction() }
    }

    override fun onBindViewHolder(holder: ControlViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE_CHANGE)) {
            val card = cards[position]
            holder.bindCardContent(card)
            val clickAction = { onCardClick(position, cards[position]) }
            holder.itemView.setOnClickListener { clickAction() }
            holder.cardContainer?.setOnClickListener { clickAction() }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = cards.size

    companion object {
        const val PAYLOAD_STATE_CHANGE = "state_change"
    }

    inner class ControlViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContainer: CardView? = itemView.findViewById(R.id.cardContainer)
        val cardInnerLayout: View? = itemView.findViewById(R.id.cardInnerLayout)
        val ivControlCardIcon: ImageView? = itemView.findViewById(R.id.ivControlCardIcon)
        val labelRow: View? = itemView.findViewById(R.id.labelRow)
        val tvControlTitle: TextView? = itemView.findViewById(R.id.tvControlTitle)

        private var currentCard: ControlCard? = null
        private var lastFocused: Boolean? = null

        fun bind(card: ControlCard, isFocused: Boolean) {
            bindCardContent(card)
            lastFocused = null
            setCenterFocused(isFocused, animate = false)
        }

        fun bindCardContent(card: ControlCard) {
            currentCard = card
            val context = itemView.context
            ivControlCardIcon?.setImageResource(card.cardIconRes)
            tvControlTitle?.text = card.title

            val iconColor = if (card.isEnabled) {
                OverlayPrefs.controlItemActiveColor(context, card.id)
            } else {
                OverlayPrefs.getCardBorder(context)
            }

            ivControlCardIcon?.let {
                ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(iconColor))
            }
        }

        fun setCenterFocused(isFocused: Boolean, animate: Boolean = true) {
            if (lastFocused == isFocused) return
            lastFocused = isFocused

            val context = itemView.context
            val standardBg = OverlayPrefs.getCardBg(context)
            val radius = OverlayPrefs.getCardCornerRadiusDp(context) * context.resources.displayMetrics.density
            cardContainer?.radius = radius
            cardContainer?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)

            val borderColor = OverlayPrefs.getBorderColor(context)
            val borderWidthDp = OverlayPrefs.getBorderWidthDp(context)
            val cardBorder = OverlayPrefs.getCardBorder(context)
            val strokeWidthPx = (borderWidthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(0)

            // Solution A: Combine fill & stroke in a single GradientDrawable so corners never bleed
            val combinedDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(standardBg)
                if (strokeWidthPx > 0) {
                    setStroke(strokeWidthPx, if (isFocused) borderColor else cardBorder)
                }
            }
            cardInnerLayout?.background = combinedDrawable

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
    }
}
