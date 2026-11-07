package com.example.consoleswitcher3.model

import android.bluetooth.BluetoothDevice
import androidx.annotation.DrawableRes

data class BluetoothDeviceItem(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val isBusy: Boolean = false,
    val statusText: String,
    @param:DrawableRes val iconRes: Int,
    val device: BluetoothDevice? = null,
    val isSettingsAction: Boolean = false
)
