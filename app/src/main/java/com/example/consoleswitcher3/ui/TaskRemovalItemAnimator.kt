package com.example.consoleswitcher3.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Item animator for the task strip that animates exactly one thing: the
 * fly-out of a card the user just closed.
 *
 * The overlay removes a closed task from the adapter the instant the button is
 * pressed, so selection, navigation and further closes are never blocked or
 * computed against stale indices. The card itself must still finish flying out
 * on screen, though. RecyclerView keeps a removed view attached as a hidden
 * "disappearing" child for exactly as long as its ItemAnimator reports it as
 * animating, which lets the card complete its animation after its data is gone
 * without ever being recycled or rebound to another task mid-flight.
 *
 * Every other change (moves, appearances, rebinds) is applied instantly, exactly
 * like a null item animator, because the strip's own XMB transform owns those
 * visuals (and default item animations caused blinking on navigation).
 */
class TaskRemovalItemAnimator : RecyclerView.ItemAnimator() {

    private class FlyOut(
        val holder: RecyclerView.ViewHolder,
        val animator: Animator,
        val onEnd: (() -> Unit)?
    ) {
        /** RecyclerView handed the view over as disappearing and waits for us. */
        var handedOver = false
        /** The fly-out animation itself has finished. */
        var animationDone = false
        var endNotified = false
        var released = false
    }

    private val flyOuts = HashMap<View, FlyOut>()

    /** True while [view] belongs to a closing card and must not be touched by layout transforms. */
    fun isFlyingOut(view: View): Boolean = flyOuts.containsKey(view)

    /** True while at least one closed card's fly-out animator is still in flight. */
    fun hasActiveFlyOuts(): Boolean = flyOuts.values.any { !it.animationDone && !it.released }

    /**
     * Starts [animator] as the fly-out of [holder]. Call this right before the
     * item is removed from the adapter. [onEnd] runs once, when the animation
     * finishes or is cut short.
     */
    fun startFlyOut(holder: RecyclerView.ViewHolder, animator: Animator, onEnd: (() -> Unit)? = null) {
        flyOuts[holder.itemView]?.let { complete(it, cancelAnimator = true) }
        val entry = FlyOut(holder, animator, onEnd)
        flyOuts[holder.itemView] = entry
        animator.addListener(object : AnimatorListenerAdapter() {
            // Also delivered after cancel(), so this can't be missed.
            override fun onAnimationEnd(animation: Animator) = onFlyOutEnded(entry)
        })
        animator.start()
    }

    /**
     * Must be called from a RecyclerListener: drops any fly-out state for a
     * holder RecyclerView recycled on its own (e.g. after a full data reset).
     */
    fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val entry = flyOuts[holder.itemView] ?: return
        if (entry.holder !== holder) return
        // Already recycled: must not dispatch a finish for it any more.
        entry.handedOver = false
        complete(entry, cancelAnimator = true, resetViewProps = true)
    }

    private fun onFlyOutEnded(entry: FlyOut) {
        if (entry.released || entry.animationDone) return
        entry.animationDone = true
        if (entry.handedOver) {
            complete(entry, cancelAnimator = false, resetViewProps = true)
        } else {
            // Layout hasn't processed the removal yet: keep the (now invisible)
            // view untouched until animateDisappearance() or a recycle claims it.
            notifyEnd(entry)
        }
    }

    private fun complete(entry: FlyOut, cancelAnimator: Boolean, resetViewProps: Boolean = true) {
        if (entry.released) return
        entry.released = true
        if (flyOuts[entry.holder.itemView] === entry) flyOuts.remove(entry.holder.itemView)
        if (cancelAnimator) entry.animator.cancel()
        val wasHandedOver = entry.handedOver
        if (wasHandedOver) {
            entry.handedOver = false
            // Detach the disappearing view from RecyclerView BEFORE resetting its
            // alpha/translationY so it can never flash back on screen for a frame.
            dispatchAnimationFinished(entry.holder)
        }
        if (resetViewProps && wasHandedOver) {
            resetView(entry.holder.itemView)
        }
        notifyEnd(entry)
        if (flyOuts.isEmpty()) dispatchAnimationsFinished()
    }

    private fun notifyEnd(entry: FlyOut) {
        if (entry.endNotified) return
        entry.endNotified = true
        entry.onEnd?.invoke()
    }

    private fun resetView(view: View) {
        view.translationY = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
    }

    override fun animateDisappearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo?
    ): Boolean {
        val entry = flyOuts[viewHolder.itemView]
        if (entry != null && entry.holder === viewHolder && !entry.released) {
            entry.handedOver = true
            if (entry.animationDone) complete(entry, cancelAnimator = false, resetViewProps = true)
            return false
        }
        dispatchAnimationFinished(viewHolder)
        return false
    }

    override fun animateAppearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo?,
        postLayoutInfo: ItemHolderInfo
    ): Boolean {
        dispatchAnimationFinished(viewHolder)
        return false
    }

    override fun animatePersistence(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo
    ): Boolean {
        dispatchAnimationFinished(viewHolder)
        return false
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo
    ): Boolean {
        dispatchAnimationFinished(oldHolder)
        if (newHolder !== oldHolder) dispatchAnimationFinished(newHolder)
        return false
    }

    /** Rebind in place (preview refreshes) instead of cross-fading a second holder. */
    override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder): Boolean = true

    override fun canReuseUpdatedViewHolder(
        viewHolder: RecyclerView.ViewHolder,
        payloads: MutableList<Any>
    ): Boolean = true

    override fun runPendingAnimations() {
        // Fly-outs start immediately in startFlyOut(); nothing is ever deferred.
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val entry = flyOuts[item.itemView] ?: return
        if (entry.holder === item) {
            complete(entry, cancelAnimator = true, resetViewProps = true)
            resetView(item.itemView)
        }
    }

    override fun endAnimations() {
        flyOuts.values.toList().forEach {
            complete(it, cancelAnimator = true, resetViewProps = true)
            resetView(it.holder.itemView)
        }
    }

    override fun isRunning(): Boolean = flyOuts.isNotEmpty()
}
