package com.example.consoleswitcher3.model

import android.bluetooth.BluetoothDevice
import androidx.annotation.DrawableRes

data class BluetoothDeviceItem(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val isBusy: Boolean = false,
    val hasError: Boolean = false,
    @param:DrawableRes val iconRes: Int,
    val device: BluetoothDevice? = null,
    val networkId: Int = -1,
    val isSettingsAction: Boolean = false
)
