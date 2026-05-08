package com.lisvpn.android.core.designsystem.component.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lisvpn.android.core.designsystem.theme.LisTheme

/**
 * Translucent glass-effect surface — used for elevated cards on top of the aurora field
 * (server selection, status chips, info panels).
 *
 * Visual recipe:
 * - Vertical gradient fill (`glassTop` → `glassBottom`) — a tiny luminance lift on top edge.
 * - 1dp hairline border (`hairline` token) — defines the card without harshness.
 * - Soft rounded corners (24dp default).
 *
 * NOT a real `BackdropFilter` blur — Compose's `Modifier.blur()` is expensive (forces an offscreen
 * compositor) and would chew battery on the Home screen. The illusion of glass comes from the
 * vertical gradient on top of the aurora field.
 */
@Composable
fun LisGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val grad = LisTheme.gradients
    val fill: Brush = Brush.verticalGradient(listOf(grad.glassTop, grad.glassBottom))
    val border: Brush = SolidColor(grad.hairline)
    val base = modifier
        .clip(shape)
        .background(brush = fill, shape = shape)
        .border(width = 1.dp, brush = border, shape = shape)
    val container = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Column(
        modifier = container.padding(contentPadding),
        content = content,
    )
}
