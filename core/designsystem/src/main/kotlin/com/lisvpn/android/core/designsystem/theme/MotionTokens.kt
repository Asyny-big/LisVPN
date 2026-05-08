package com.lisvpn.android.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * LisVPN motion language. Spring-physics first; tweens are reserved for color/alpha and other
 * continuous fades where overshoot is undesirable.
 *
 * The values here are tokens, not raw durations — UI code should reference `LisTheme.motion.fast`
 * rather than literal milliseconds so we can retune the whole app from a single place.
 */
@Immutable
data class LisMotion(
    val durationFast: Int = 180,
    val durationMedium: Int = 320,
    val durationSlow: Int = 560,
    val durationOrbBreath: Int = 2400,
    val durationOrbPulse: Int = 1100,
    val durationAuroraSweep: Int = 9000,
)

internal val DefaultLisMotion = LisMotion()

val LocalLisMotion = staticCompositionLocalOf { DefaultLisMotion }

/** Snappy spring — for property changes that should feel responsive but stable (no overshoot). */
fun <T> springSnappy(): AnimationSpec<T> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Gentle bounce — for hero elements like the orb where a soft overshoot adds liveness. */
fun <T> springGentle(): AnimationSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow,
)

/** Fast tween — for color crossfades, hairline opacity. */
fun <T> tweenFast(): AnimationSpec<T> = tween(durationMillis = DefaultLisMotion.durationFast)

/** Medium tween — for state title/subtitle text crossfades. */
fun <T> tweenMedium(): AnimationSpec<T> = tween(durationMillis = DefaultLisMotion.durationMedium)
