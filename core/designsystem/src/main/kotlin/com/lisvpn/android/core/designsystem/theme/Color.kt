package com.lisvpn.android.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * LisVPN palette. Neutral, calm, with a single warm accent (the «fox» — lis).
 *
 * Naming follows Material 3 role tokens; raw colour values are private to keep semantics
 * the only public contract.
 */
internal object LisPalette {
    val BrandAmber = Color(0xFFFF8A3D)
    val BrandAmberDeep = Color(0xFFE56F1F)
    val BrandAmberSoft = Color(0xFFFFE0C8)

    val NeutralLight0 = Color(0xFFFAF7F2)
    val NeutralLight10 = Color(0xFFF1ECE3)
    val NeutralLight20 = Color(0xFFDFD7C9)
    val NeutralLight30 = Color(0xFFB6AC9A)
    val NeutralLight90 = Color(0xFF1F1B16)

    val NeutralDark0 = Color(0xFF0B0F14)
    val NeutralDark10 = Color(0xFF12181F)
    val NeutralDark20 = Color(0xFF1B232C)
    val NeutralDark30 = Color(0xFF2A323D)
    val NeutralDark90 = Color(0xFFE7EDF4)

    val SuccessGreen = Color(0xFF34C759)
    val SuccessGreenDim = Color(0xFF1F8A3F)
    val WarningAmber = Color(0xFFFFB85C)
    val ErrorRed = Color(0xFFFF453A)
}

/**
 * Status colours used by [com.lisvpn.android.core.designsystem.component.StatusOrb] and
 * other presence indicators. Distinct from M3 colour scheme so they remain stable across themes.
 */
data class LisStatusColors(
    val idle: Color,
    val connecting: Color,
    val connected: Color,
    val error: Color,
    val onIdle: Color,
    val onConnected: Color,
)

internal val LightStatusColors = LisStatusColors(
    idle = LisPalette.NeutralLight20,
    connecting = LisPalette.BrandAmber,
    connected = LisPalette.SuccessGreen,
    error = LisPalette.ErrorRed,
    onIdle = LisPalette.NeutralLight90,
    onConnected = Color.White,
)

internal val DarkStatusColors = LisStatusColors(
    idle = LisPalette.NeutralDark30,
    connecting = LisPalette.BrandAmber,
    connected = LisPalette.SuccessGreen,
    error = LisPalette.ErrorRed,
    onIdle = LisPalette.NeutralDark90,
    onConnected = Color(0xFF062B12),
)
