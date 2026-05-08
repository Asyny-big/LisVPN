package com.lisvpn.android.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Single entry point for theming. Wraps [MaterialTheme] and exposes a custom
 * [LocalLisStatusColors] composition local for VPN-specific status indicators.
 *
 * Material You is opt-in (Android 12+) and disabled by default to keep the brand cohesive across
 * devices — turn it on per-screen if needed via [enableDynamicColor].
 */
val LocalLisStatusColors = staticCompositionLocalOf<LisStatusColors> { LightStatusColors }

@Composable
fun LisTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    enableDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        enableDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        useDarkTheme -> darkColorScheme(
            primary = LisPalette.BrandAmber,
            onPrimary = LisPalette.NeutralDark0,
            primaryContainer = LisPalette.BrandAmberDeep,
            onPrimaryContainer = LisPalette.BrandAmberSoft,
            secondary = LisPalette.NeutralDark30,
            onSecondary = LisPalette.NeutralDark90,
            background = LisPalette.SurfaceDark0,
            onBackground = LisPalette.NeutralDark90,
            surface = LisPalette.SurfaceDark1,
            onSurface = LisPalette.NeutralDark90,
            surfaceVariant = LisPalette.SurfaceDark2,
            onSurfaceVariant = LisPalette.NeutralDark90.copy(alpha = 0.78f),
            error = LisPalette.ErrorRed,
            onError = LisPalette.NeutralDark0,
        )

        else -> lightColorScheme(
            primary = LisPalette.BrandAmberDeep,
            onPrimary = LisPalette.NeutralLight0,
            primaryContainer = LisPalette.BrandAmberSoft,
            onPrimaryContainer = LisPalette.NeutralLight90,
            secondary = LisPalette.NeutralLight30,
            onSecondary = LisPalette.NeutralLight90,
            background = LisPalette.NeutralLight0,
            onBackground = LisPalette.NeutralLight90,
            surface = LisPalette.NeutralLight10,
            onSurface = LisPalette.NeutralLight90,
            surfaceVariant = LisPalette.NeutralLight20,
            onSurfaceVariant = LisPalette.NeutralLight90.copy(alpha = 0.78f),
            error = LisPalette.ErrorRed,
            onError = LisPalette.NeutralLight0,
        )
    }

    val statusColors = if (useDarkTheme) DarkStatusColors else LightStatusColors
    val gradients = if (useDarkTheme) DarkLisGradients else LightLisGradients

    CompositionLocalProvider(
        LocalLisStatusColors provides statusColors,
        LocalLisGradients provides gradients,
        LocalLisSpacing provides DefaultLisSpacing,
        LocalLisMotion provides DefaultLisMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LisTypography,
            shapes = LisShapes,
            content = content,
        )
    }
}

object LisTheme {
    val statusColors: LisStatusColors
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalLisStatusColors.current

    val gradients: LisGradients
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalLisGradients.current

    val spacing: LisSpacing
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalLisSpacing.current

    val motion: LisMotion
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalLisMotion.current
}
