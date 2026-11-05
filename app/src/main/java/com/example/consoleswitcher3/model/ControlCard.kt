package com.example.consoleswitcher3.model

import androidx.annotation.DrawableRes

data class ControlCard(
    val id: String,
    val title: String,
    val statusPrimary: String,
    val statusSecondary: String,
    @param:DrawableRes val iconRes: Int,
    @param:DrawableRes val cardIconRes: Int,
    val isEnabled: Boolean = true
)

