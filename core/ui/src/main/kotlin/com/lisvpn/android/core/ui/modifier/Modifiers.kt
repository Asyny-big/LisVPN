package com.lisvpn.android.core.ui.modifier

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Edge-to-edge sane defaults: respect status + nav bars without manually plumbing per screen.
 */
fun Modifier.systemBarsContentPadding(
    sides: WindowInsetsSides = WindowInsetsSides.Top + WindowInsetsSides.Bottom,
): Modifier = composed {
    windowInsetsPadding(WindowInsets.systemBars.only(sides))
}

@Composable
fun safeContentInsets(): WindowInsets = WindowInsets.systemBars
