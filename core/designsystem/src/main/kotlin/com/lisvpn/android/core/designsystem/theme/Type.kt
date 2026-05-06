package com.lisvpn.android.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Compose-friendly type scale.
 *
 * Default font is system sans (Roboto on Android, falls back gracefully on OEM forks).
 * Custom font (Inter / Manrope) can be swapped here later without touching call sites — we keep all
 * `TextStyle` consumers using `MaterialTheme.typography.X` exclusively.
 */
private val SystemSans = FontFamily.SansSerif

internal val LisTypography = Typography(
    displayLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.2).sp),

    headlineLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),

    titleLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
