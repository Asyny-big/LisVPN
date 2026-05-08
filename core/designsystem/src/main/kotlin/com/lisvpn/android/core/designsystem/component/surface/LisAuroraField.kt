package com.lisvpn.android.core.designsystem.component.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lisvpn.android.core.designsystem.theme.LisTheme

/**
 * Ambient background — three slowly-drifting radial blobs that sit behind every screen and lend
 * the app a sense of depth without dominating the content.
 *
 * Design intent:
 * - Softer than a static gradient, calmer than a particle system.
 * - Drift speed (~9 s sweep) is below the perceptual threshold for distraction.
 * - Three colour stops: purple → amber → teal — same hues as the orb's idle/connecting/connected
 *   gradients, so the background "echoes" the orb without copying it.
 *
 * Performance:
 * - Three GPU-only `drawCircle` calls with radial gradients. No bitmaps, no per-frame allocations
 *   beyond `Brush` (which Skia caches by colour stop list).
 * - When `intensity = 0f` the blobs collapse to zero alpha and the GPU short-circuits — useful
 *   when the user explicitly disables decorative motion via system settings.
 */
@Composable
fun LisAuroraField(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val grad = LisTheme.gradients
    val animatedIntensity by animateColorAsState(
        targetValue = Color.White.copy(alpha = intensity.coerceIn(0f, 1f)),
        animationSpec = tween(durationMillis = 800),
        label = "auroraIntensity",
    )
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDrift",
    )
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val k = animatedIntensity.alpha

            // Blob 1 — top-left, purple. Drifts vertically.
            val c1 = Offset(w * 0.15f, h * (0.18f + 0.08f * drift))
            val r1 = w * 0.7f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(grad.auroraStart.copy(alpha = grad.auroraStart.alpha * k), Color.Transparent),
                    center = c1,
                    radius = r1,
                ),
                radius = r1,
                center = c1,
            )

            // Blob 2 — center, amber. Drifts in opposite direction.
            val c2 = Offset(w * 0.6f, h * (0.45f - 0.05f * drift))
            val r2 = w * 0.55f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(grad.auroraMid.copy(alpha = grad.auroraMid.alpha * k), Color.Transparent),
                    center = c2,
                    radius = r2,
                ),
                radius = r2,
                center = c2,
            )

            // Blob 3 — bottom-right, teal. Drifts vertically.
            val c3 = Offset(w * 0.9f, h * (0.85f + 0.05f * drift))
            val r3 = w * 0.6f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(grad.auroraEnd.copy(alpha = grad.auroraEnd.alpha * k), Color.Transparent),
                    center = c3,
                    radius = r3,
                ),
                radius = r3,
                center = c3,
            )
        }
        content()
    }
}
