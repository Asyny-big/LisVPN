package com.lisvpn.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens aligned to a 4dp baseline grid.
 *
 * Use as `LisTheme.spacing.lg` instead of hard-coded `16.dp` literals so screens stay rhythm-consistent
 * and tokens can be retuned in one place.
 */
@Immutable
data class LisSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val gutter: Dp = 24.dp,
    val cardCorner: Dp = 24.dp,
    val pillCorner: Dp = 999.dp,
)

internal val DefaultLisSpacing = LisSpacing()

val LocalLisSpacing = staticCompositionLocalOf { DefaultLisSpacing }
