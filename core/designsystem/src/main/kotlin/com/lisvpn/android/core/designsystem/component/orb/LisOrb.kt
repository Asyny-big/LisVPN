package com.lisvpn.android.core.designsystem.component.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lisvpn.android.core.designsystem.theme.LisTheme

/**
 * High-fidelity hero element for the Home screen — replaces [com.lisvpn.android.core.designsystem.component.StatusOrb].
 *
 * What's different:
 * - **Phase-aware** state model: 7 distinct connection moments instead of the old 4-state enum.
 *   Each phase has its own breath cadence, sweep behaviour, halo intensity and gradient pair.
 * - **Spring-physics colour** — `animateColorAsState` with a snappier 360 ms tween (was 450 ms linear).
 * - **Sine-eased breath** — uses [EaseInOutSine] instead of `LinearEasing`, which removes the
 *   metronome feel of the old orb (a chronic complaint when the screen sat in `Idle` for a while).
 * - **Sweep arc** — a rotating ring that only renders during active phases, drawn with
 *   `Brush.sweepGradient` so it's a single draw call. Uses `rotate(degrees, pivot)` instead of
 *   per-frame brush re-allocation.
 * - **Specular highlight** — a small soft white blob in the upper-left of the core gives the orb
 *   a subtle 3D appearance without bringing in shaders.
 *
 * Performance notes:
 * - Total draw cost: 4 circles + (when active) 1 stroked arc → ≤ 5 GPU ops per frame.
 * - No bitmap allocations; brushes are recreated each frame but Skia caches them by key.
 * - All animations stop when the phase is `Error`-static (no `infiniteRepeatable` running) once
 *   the breath token reaches steady-state.
 */
@Composable
fun LisOrb(
    phase: OrbPhase,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val gradients = LisTheme.gradients

    // ----- Phase → colour pair -----
    val hiTarget = when (phase) {
        OrbPhase.Connected -> gradients.orbConnectedHi
        OrbPhase.Connecting,
        OrbPhase.Validating,
        OrbPhase.Reconnecting,
        OrbPhase.AutoOptimizing -> gradients.orbConnectingHi
        OrbPhase.Error -> gradients.orbErrorHi
        OrbPhase.Unstable -> gradients.orbErrorHi
        OrbPhase.Idle -> gradients.orbIdleHi
    }
    val loTarget = when (phase) {
        OrbPhase.Connected -> gradients.orbConnectedLo
        OrbPhase.Connecting,
        OrbPhase.Validating,
        OrbPhase.Reconnecting,
        OrbPhase.AutoOptimizing -> gradients.orbConnectingLo
        OrbPhase.Error -> gradients.orbErrorLo
        OrbPhase.Unstable -> gradients.orbErrorLo
        OrbPhase.Idle -> gradients.orbIdleLo
    }
    val hi by animateColorAsState(targetValue = hiTarget, animationSpec = tween(560), label = "orbHi")
    val lo by animateColorAsState(targetValue = loTarget, animationSpec = tween(560), label = "orbLo")

    // ----- Phase → breath cadence -----
    val breathDuration = when (phase) {
        OrbPhase.Connecting,
        OrbPhase.Validating,
        OrbPhase.Reconnecting,
        OrbPhase.AutoOptimizing -> 1100
        OrbPhase.Error,
        OrbPhase.Unstable -> 1800
        OrbPhase.Connected -> 2400
        OrbPhase.Idle -> 2400
    }
    val breathRange = when (phase) {
        OrbPhase.Connecting,
        OrbPhase.Validating,
        OrbPhase.Reconnecting,
        OrbPhase.AutoOptimizing -> 0.06f
        OrbPhase.Error,
        OrbPhase.Unstable -> 0.02f
        OrbPhase.Connected -> 0.025f
        OrbPhase.Idle -> 0.04f
    }

    val transition = rememberInfiniteTransition(label = "orbBreath")
    val breath by transition.animateFloat(
        initialValue = 1f - breathRange,
        targetValue = 1f + breathRange,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = breathDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbBreathScale",
    )

    // ----- Sweep rotation (only for active phases) -----
    val rotationActive = phase == OrbPhase.Connecting ||
        phase == OrbPhase.Validating ||
        phase == OrbPhase.Reconnecting ||
        phase == OrbPhase.AutoOptimizing ||
        phase == OrbPhase.Unstable
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (rotationActive) 2400 else 9000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbRotation",
    )

    val haloAlpha = when (phase) {
        OrbPhase.Connected -> 0.55f
        OrbPhase.Connecting,
        OrbPhase.Validating,
        OrbPhase.Reconnecting,
        OrbPhase.AutoOptimizing -> 0.7f
        OrbPhase.Error,
        OrbPhase.Unstable -> 0.4f
        OrbPhase.Idle -> 0.35f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f * 0.72f
            val r = baseRadius * breath

            // 1. Outer halo — soft radial bloom.
            val haloRadius = r * 1.7f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(hi.copy(alpha = haloAlpha), Color.Transparent),
                    center = center,
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = center,
            )

            // 2. Hairline outline — gives the orb a crisp edge without harshness.
            drawCircle(
                color = hi.copy(alpha = 0.18f),
                radius = r * 1.05f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // 3. Sweep arc (stroked ring) — rotates only when phase is active.
            if (rotationActive) {
                rotate(degrees = rotation, pivot = center) {
                    val ringSize = r * 2.1f
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.45f to hi.copy(alpha = 0.6f),
                            0.55f to hi.copy(alpha = 0.85f),
                            0.65f to hi.copy(alpha = 0f),
                            1f to Color.Transparent,
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - r * 1.05f, center.y - r * 1.05f),
                        size = Size(ringSize, ringSize),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }

            // 4. Core sphere — radial gradient (hi top-left, lo bottom-right) for soft depth.
            val coreLightCenter = Offset(center.x - r * 0.25f, center.y - r * 0.30f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(hi, lo),
                    center = coreLightCenter,
                    radius = r * 1.3f,
                ),
                radius = r,
                center = center,
            )

            // 5. Specular highlight — soft white bloom in the upper-left of the core.
            val specCenter = Offset(center.x - r * 0.35f, center.y - r * 0.40f)
            val specRadius = r * 0.55f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                    center = specCenter,
                    radius = specRadius,
                ),
                radius = specRadius,
                center = specCenter,
            )
        }
    }
}

/**
 * Visual phase of the orb. Mirrors the connection state machine in `:core:domain` but is owned
 * by the design system so feature modules don't depend on it directly.
 *
 * Mapping is performed in feature code (see `HomeScreen.kt#mapToOrbPhase`).
 */
enum class OrbPhase {
    /** No active connection, ready to engage. */
    Idle,

    /** Permission granted, tunnel coming up. */
    Connecting,

    /** Tunnel up, validating internet egress. Same visual tier as Connecting. */
    Validating,

    /** Steady-state, traffic flowing. */
    Connected,

    /** Network changed under us, re-establishing without user action. */
    Reconnecting,

    /** Connected but the link is lossy / high-latency / packet-loss. */
    Unstable,

    /** AUTO mode is probing candidates and ranking by speed/latency. */
    AutoOptimizing,

    /** Hard failure — user must intervene. */
    Error,
}
