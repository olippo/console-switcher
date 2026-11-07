package com.example.consoleswitcher3.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.consoleswitcher3.R
import com.example.consoleswitcher3.model.BluetoothDeviceItem
import com.example.consoleswitcher3.prefs.OverlayPrefs

class BluetoothDeviceAdapter(
    private var items: List<BluetoothDeviceItem>,
    private val onItemClick: (Int, BluetoothDeviceItem) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].address.hashCode().toLong()
    }

    var selectedPosition: Int = 0
        set(value) {
            val old = field
            field = value
            if (old != value) {
                if (old in items.indices) notifyItemChanged(old)
                if (value in items.indices) notifyItemChanged(value)
            }
        }

    fun updateDevices(newItems: List<BluetoothDeviceItem>) {
        val oldList = this.items
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].address == newItems[newItemPosition].address
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return oldItem.name == newItem.name &&
                        oldItem.isConnected == newItem.isConnected &&
                        oldItem.isBusy == newItem.isBusy &&
                        oldItem.statusText == newItem.statusText &&
                        oldItem.iconRes == newItem.iconRes
            }
        })

        val prevAddress = items.getOrNull(selectedPosition)?.address
        this.items = newItems.toList()
        val preservedIdx = if (prevAddress != null) {
            items.indexOfFirst { it.address == prevAddress }
        } else -1
        selectedPosition = when {
            preservedIdx >= 0 -> preservedIdx
            items.isEmpty() -> 0
            else -> selectedPosition.coerceIn(0, items.size - 1)
        }
        diffResult.dispatchUpdatesTo(this)
    }

    fun getSelectedDevice(): BluetoothDeviceItem? {
        return items.getOrNull(selectedPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position == selectedPosition)
        holder.itemView.setOnClickListener {
            selectedPosition = position
            onItemClick(position, item)
        }
    }

    override fun getItemCount(): Int = items.size

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemRoot: View = itemView.findViewById(R.id.itemRoot)
        private val ivDeviceIcon: ImageView = itemView.findViewById(R.id.ivDeviceIcon)
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvDeviceStatus: TextView = itemView.findViewById(R.id.tvDeviceStatus)
        private val ivDeviceStatusCheck: ImageView = itemView.findViewById(R.id.ivDeviceStatusCheck)
        private val pbDeviceLoading: ProgressBar = itemView.findViewById(R.id.pbDeviceLoading)
        private val vItemBorder: View = itemView.findViewById(R.id.vItemBorder)

        fun bind(item: BluetoothDeviceItem, isSelected: Boolean) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val focusedBorderColor = OverlayPrefs.getBorderColor(context)
            val cardFocusedBg = OverlayPrefs.getCardFocusedBg(context)
            // Menus have a hardcoded 8dp corner radius
            val radiusPx = 8f * density

            ivDeviceIcon.setImageResource(item.iconRes)
            tvDeviceName.text = item.name

            val primaryText = ContextCompat.getColor(context, R.color.deck_text_primary)
            ImageViewCompat.setImageTintList(ivDeviceIcon, ColorStateList.valueOf(primaryText))
            ImageViewCompat.setImageTintList(ivDeviceStatusCheck, ColorStateList.valueOf(primaryText))
            pbDeviceLoading.indeterminateTintList = ColorStateList.valueOf(primaryText)

            tvDeviceName.setTextColor(primaryText)

            val targetBusyVisibility = if (item.isBusy) View.VISIBLE else View.GONE
            val targetCheckVisibility = if (!item.isBusy && item.isConnected) View.VISIBLE else View.GONE

            if (pbDeviceLoading.visibility != targetBusyVisibility) {
                pbDeviceLoading.visibility = targetBusyVisibility
            }
            if (ivDeviceStatusCheck.visibility != targetCheckVisibility) {
                ivDeviceStatusCheck.visibility = targetCheckVisibility
            }
            if (tvDeviceStatus.visibility != View.GONE) {
                tvDeviceStatus.visibility = View.GONE
            }

            if (isSelected) {
                val borderWidthDp = OverlayPrefs.getBorderWidthDp(context).coerceIn(1.5f, 3.0f)
                val strokePx = (borderWidthDp * density).toInt().coerceAtLeast(2)
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(cardFocusedBg)
                    setStroke(strokePx, focusedBorderColor)
                }
                itemRoot.background = bgDrawable
            } else {
                itemRoot.background = null
            }
            vItemBorder.background = null
        }
    }
}
