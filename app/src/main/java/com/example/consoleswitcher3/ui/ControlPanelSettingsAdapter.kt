package com.example.consoleswitcher3.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.prefs.OverlayPrefs
import com.google.android.material.card.MaterialCardView
import java.util.Collections

class ControlPanelSettingsAdapter(
    initialItems: List<OverlayPrefs.ControlItemConfig>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemsChanged: (List<OverlayPrefs.ControlItemConfig>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Entry {
        data class Item(val id: String, var enabled: Boolean) : Entry()
        data object BottomDivider : Entry()
    }

    companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_DIVIDER = 1
    }

    private val entries = mutableListOf<Entry>()

    init {
        setItems(initialItems, notify = false)
        setHasStableIds(true)
    }

    fun setItems(items: List<OverlayPrefs.ControlItemConfig>, notify: Boolean = true) {
        entries.clear()
        for (item in items) {
            if (item.enabled) {
                entries.add(Entry.Item(item.id, true))
            }
        }
        entries.add(Entry.BottomDivider)
        for (item in items) {
            if (!item.enabled) {
                entries.add(Entry.Item(item.id, false))
            }
        }
        if (notify) {
            notifyDataSetChanged()
        }
    }

    fun exportItems(): List<OverlayPrefs.ControlItemConfig> {
        val dividerIdx = getDividerIndex()
        val result = mutableListOf<OverlayPrefs.ControlItemConfig>()
        for (i in entries.indices) {
            val item = entries[i] as? Entry.Item ?: continue
            val enabled = if (dividerIdx >= 0) i < dividerIdx else item.enabled
            result.add(OverlayPrefs.ControlItemConfig(item.id, enabled))
        }
        return result
    }

    fun getDividerIndex(): Int {
        return entries.indexOfFirst { it is Entry.BottomDivider }
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in entries.indices || toPosition !in entries.indices || fromPosition == toPosition) {
            return false
        }
        if (entries[fromPosition] !is Entry.Item) return false

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(entries, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(entries, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun onDragFinished() {
        val dividerIdx = getDividerIndex()
        if (dividerIdx >= 0) {
            for (i in entries.indices) {
                val item = entries[i] as? Entry.Item ?: continue
                item.enabled = i < dividerIdx
            }
        }
        onItemsChanged(exportItems())
    }

    override fun getItemId(position: Int): Long {
        return when (val entry = entries[position]) {
            is Entry.Item -> entry.id.hashCode().toLong()
            is Entry.BottomDivider -> -999999L
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (entries[position]) {
            is Entry.Item -> VIEW_TYPE_ITEM
            is Entry.BottomDivider -> VIEW_TYPE_DIVIDER
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_DIVIDER) {
            val view = inflater.inflate(R.layout.item_control_panel_divider, parent, false)
            DividerViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_control_panel_setting, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is Entry.Item -> {
                val dividerIdx = getDividerIndex()
                val isEnabled = if (dividerIdx >= 0) position < dividerIdx else entry.enabled
                entry.enabled = isEnabled
                (holder as ItemViewHolder).bind(entry)
            }
            is Entry.BottomDivider -> (holder as DividerViewHolder).bind()
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardRow: MaterialCardView = itemView.findViewById(R.id.cardControlSettingRow)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        val ivItemIcon: ImageView = itemView.findViewById(R.id.ivItemIcon)
        val tvItemTitle: TextView = itemView.findViewById(R.id.tvItemTitle)

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: Entry.Item) {
            ivItemIcon.setImageResource(OverlayPrefs.controlItemIconRes(item.id))
            tvItemTitle.text = OverlayPrefs.controlItemTitle(item.id)
            itemView.alpha = if (item.enabled) 1.0f else 0.5f

            ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }
    }

    class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            // Static section header for the hidden items area
        }
    }
}
