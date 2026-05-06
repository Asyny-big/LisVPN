package com.lisvpn.android.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lisvpn.android.core.designsystem.theme.LisTheme

/**
 * The hero element of the Home screen.
 *
 * Renders a circular orb whose colour, glow intensity and pulse cadence reflect [state].
 * Tapping triggers [onTap] — typical use is "connect / disconnect / cancel" depending on state.
 *
 * Implementation notes:
 *  - Uses [Canvas] (not nested [Box]es) for resolution-independent radial glow without overdraw.
 *  - All animations are [infiniteRepeatable] when active, off when idle/connected steady-state.
 *  - The component is intentionally agnostic of `VpnState` from `:core:domain` — pass mapped values.
 */
@Composable
fun StatusOrb(
    state: StatusOrbState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LisTheme.statusColors
    val targetColor = when (state) {
        StatusOrbState.Idle -> statusColors.idle
        StatusOrbState.Connecting -> statusColors.connecting
        StatusOrbState.Connected -> statusColors.connected
        StatusOrbState.Error -> statusColors.error
    }
    val coreColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(450), label = "orbColor")

    val transition = rememberInfiniteTransition(label = "orbPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = if (state == StatusOrbState.Idle) 0.0f else 0.55f,
        targetValue = if (state == StatusOrbState.Connecting) 0.95f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == StatusOrbState.Connecting) 900 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbPulseAlpha",
    )
    val pulseRadius by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = if (state == StatusOrbState.Connecting) 1.18f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == StatusOrbState.Connecting) 900 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbPulseRadius",
    )

    Box(
        modifier = modifier
            .size(220.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f * 0.78f

            // Outer glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = pulseAlpha), Color.Transparent),
                    center = center,
                    radius = baseRadius * pulseRadius * 1.35f,
                ),
                radius = baseRadius * pulseRadius * 1.35f,
                center = center,
            )
            // Halo ring
            drawCircle(
                color = coreColor.copy(alpha = 0.18f),
                radius = baseRadius * 1.05f,
                center = center,
            )
            // Core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor,
                        coreColor.copy(alpha = 0.85f),
                    ),
                    center = center,
                    radius = baseRadius,
                ),
                radius = baseRadius,
                center = center,
            )
        }
    }
}

/**
 * Visual-only status enum so the design system module never depends on VPN domain types.
 * Mappers in feature modules translate `VpnState` → `StatusOrbState`.
 */
enum class StatusOrbState { Idle, Connecting, Connected, Error }
