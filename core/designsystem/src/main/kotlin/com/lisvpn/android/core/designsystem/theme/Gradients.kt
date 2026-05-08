package com.lisvpn.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Gradient/depth tokens used by `LisOrb`, `LisAuroraField` and `LisGlassCard`.
 *
 * Kept as semantic colour pairs (hi/lo per phase) instead of raw `Brush` instances so consumers
 * can build brushes on demand at draw-time without paying brush-allocation cost on the hot path
 * (Compose `Brush` is referentially-unstable and would force unnecessary recompositions if cached
 * inside this `@Immutable` data class).
 */
@Immutable
data class LisGradients(
    // Aurora field — three blobs used as ambient background lighting.
    val auroraStart: Color,
    val auroraMid: Color,
    val auroraEnd: Color,

    // Orb hi (top-left highlight) / lo (bottom-right shade) per phase.
    val orbIdleHi: Color,
    val orbIdleLo: Color,
    val orbConnectingHi: Color,
    val orbConnectingLo: Color,
    val orbConnectedHi: Color,
    val orbConnectedLo: Color,
    val orbErrorHi: Color,
    val orbErrorLo: Color,

    // Glass surface tokens — top and bottom of vertical fill.
    val glassTop: Color,
    val glassBottom: Color,
    val hairline: Color,
)

internal val DarkLisGradients = LisGradients(
    auroraStart = LisPalette.BrandAuroraPurple.copy(alpha = 0.55f),
    auroraMid = LisPalette.BrandAmber.copy(alpha = 0.20f),
    auroraEnd = LisPalette.BrandAuroraTeal.copy(alpha = 0.45f),

    orbIdleHi = LisPalette.BrandAuroraPurple,
    orbIdleLo = LisPalette.SurfaceDark2,
    orbConnectingHi = LisPalette.BrandAmber,
    orbConnectingLo = LisPalette.BrandAuroraPurple,
    orbConnectedHi = LisPalette.SuccessGreen,
    orbConnectedLo = LisPalette.BrandAuroraTeal,
    orbErrorHi = LisPalette.ErrorRed,
    orbErrorLo = Color(0xFF7A1814),

    glassTop = Color(0x14FFFFFF),
    glassBottom = Color(0x06FFFFFF),
    hairline = LisPalette.HairlineDark,
)

internal val LightLisGradients = LisGradients(
    auroraStart = LisPalette.BrandAuroraPurple.copy(alpha = 0.20f),
    auroraMid = LisPalette.BrandAmber.copy(alpha = 0.10f),
    auroraEnd = LisPalette.BrandAuroraTeal.copy(alpha = 0.18f),

    orbIdleHi = LisPalette.BrandAuroraPurple,
    orbIdleLo = LisPalette.NeutralLight20,
    orbConnectingHi = LisPalette.BrandAmber,
    orbConnectingLo = LisPalette.BrandAuroraPurple,
    orbConnectedHi = LisPalette.SuccessGreen,
    orbConnectedLo = LisPalette.BrandAuroraTeal,
    orbErrorHi = LisPalette.ErrorRed,
    orbErrorLo = Color(0xFFB22B22),

    glassTop = Color(0x1A000000),
    glassBottom = Color(0x08000000),
    hairline = LisPalette.HairlineLight,
)

val LocalLisGradients = staticCompositionLocalOf { DarkLisGradients }
