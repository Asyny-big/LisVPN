package com.lisvpn.android.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lisvpn.android.core.designsystem.component.LisPrimaryButton
import com.lisvpn.android.core.designsystem.component.StatusOrbState
import com.lisvpn.android.core.designsystem.component.orb.LisOrb
import com.lisvpn.android.core.designsystem.component.orb.OrbPhase
import com.lisvpn.android.core.designsystem.component.surface.LisAuroraField
import com.lisvpn.android.core.designsystem.component.surface.LisGlassCard
import com.lisvpn.android.core.designsystem.theme.LisTheme

/**
 * Home v2 — the only screen most users ever see.
 *
 * Layout (top → bottom):
 *  - `LisAuroraField` ambient background (whole screen)
 *  - `TopAppBar` (transparent) — brand + servers + settings
 *  - Hero block (centred): `LisOrb` + animated headline + animated subtitle
 *  - Bottom dock (`LisGlassCard`):
 *      - Mode pill (Auto / Manual)
 *      - Manual server picker (collapsed, only when `Manual`)
 *      - Primary CTA (`Подключить` / `Отключить` / loading state)
 *      - Profiles secondary action
 *
 * Premium feel comes from the orb + aurora + crossfaded copy. The screen never reflows
 * its hero positioning when state changes — only the orb phase, headline, subtitle and CTA
 * label crossfade in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onConnectionModeSelected: (HomeConnectionMode) -> Unit,
    onServerSelected: (String) -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
) {
    val phase = remember(state.orb, state.optimizer, state.errorMessage) { mapToOrbPhase(state) }
    val auroraIntensity = phase.auroraIntensity()

    LisAuroraField(
        modifier = Modifier.fillMaxSize(),
        intensity = auroraIntensity,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "LisVPN",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    },
                    actions = {
                        IconButton(onClick = onNavigateToServers) {
                            Icon(Icons.Outlined.Public, contentDescription = "Серверы")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            HomeBody(
                state = state,
                phase = phase,
                isBusy = isBusy,
                onPrimaryAction = onPrimaryAction,
                onConnectionModeSelected = onConnectionModeSelected,
                onServerSelected = onServerSelected,
                onNavigateToProfiles = onNavigateToProfiles,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun HomeBody(
    state: HomeUiState,
    phase: OrbPhase,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onConnectionModeSelected: (HomeConnectionMode) -> Unit,
    onServerSelected: (String) -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LisTheme.spacing
    Column(
        modifier = modifier.padding(horizontal = spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.4f))
        Hero(
            phase = phase,
            title = state.title,
            subtitle = state.subtitle,
            onTap = onPrimaryAction,
        )
        Spacer(Modifier.weight(1f))
        BottomDock(
            state = state,
            isBusy = isBusy,
            onPrimaryAction = onPrimaryAction,
            onConnectionModeSelected = onConnectionModeSelected,
            onServerSelected = onServerSelected,
            onNavigateToProfiles = onNavigateToProfiles,
        )
        Spacer(Modifier.heightIn(min = spacing.lg))
    }
}

@Composable
private fun Hero(
    phase: OrbPhase,
    title: String,
    subtitle: String?,
    onTap: () -> Unit,
) {
    val spacing = LisTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LisOrb(
            phase = phase,
            onTap = onTap,
        )

        // Crossfade headline so state transitions feel cohesive with the orb.
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(tween(durationMillis = 320)) + slideInVertically(tween(durationMillis = 320)) { it / 6 })
                    .togetherWith(fadeOut(tween(durationMillis = 200)) + slideOutVertically(tween(durationMillis = 200)) { -it / 6 })
            },
            label = "homeTitle",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
        }

        AnimatedContent(
            targetState = subtitle.orEmpty(),
            transitionSpec = { fadeIn(tween(280)).togetherWith(fadeOut(tween(160))) },
            label = "homeSubtitle",
        ) { current ->
            if (current.isNotBlank()) {
                Text(
                    text = current,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.size(0.dp))
            }
        }
    }
}

@Composable
private fun BottomDock(
    state: HomeUiState,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onConnectionModeSelected: (HomeConnectionMode) -> Unit,
    onServerSelected: (String) -> Unit,
    onNavigateToProfiles: () -> Unit,
) {
    val spacing = LisTheme.spacing
    LisGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 280)),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.lg),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            AnimatedVisibility(visible = state.showImportPrompt) {
                LisPrimaryButton(
                    text = "Импортировать подписку",
                    onClick = onNavigateToProfiles,
                )
            }

            AnimatedVisibility(visible = !state.showImportPrompt) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    ConnectionModeSwitch(
                        selected = state.connectionMode,
                        enabled = !isBusy && (state.canConnect || state.canDisconnect),
                        onSelected = onConnectionModeSelected,
                    )

                    AnimatedVisibility(
                        visible = state.connectionMode == HomeConnectionMode.Manual,
                    ) {
                        ManualServerPicker(
                            servers = state.servers,
                            enabled = !isBusy && state.canConnect,
                            onServerSelected = onServerSelected,
                        )
                    }

                    LisPrimaryButton(
                        text = if (state.canDisconnect) "Отключить" else "Подключить",
                        onClick = onPrimaryAction,
                        enabled = state.canConnect || state.canDisconnect,
                        loading = isBusy,
                    )

                    AnimatedVisibility(visible = state.statusMessage != null) {
                        state.statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    TextButton(onClick = onNavigateToProfiles) {
                        Text("Профили")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeSwitch(
    selected: HomeConnectionMode,
    enabled: Boolean,
    onSelected: (HomeConnectionMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModePill(
            title = "Авто",
            subtitle = "Лучший сервер",
            selected = selected == HomeConnectionMode.Auto,
            enabled = enabled,
            onClick = { onSelected(HomeConnectionMode.Auto) },
        )
        ModePill(
            title = "Вручную",
            subtitle = "Выбор сервера",
            selected = selected == HomeConnectionMode.Manual,
            enabled = enabled,
            onClick = { onSelected(HomeConnectionMode.Manual) },
        )
    }
}

@Composable
private fun RowScope.ModePill(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .weight(1f)
            .clickable(enabled = enabled && !selected, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.45f),
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (selected) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ManualServerPicker(
    servers: List<HomeServerOption>,
    enabled: Boolean,
    onServerSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        servers.forEach { server ->
            ManualServerRow(
                server = server,
                enabled = enabled,
                onClick = { onServerSelected(server.id) },
            )
        }
    }
}

@Composable
private fun ManualServerRow(
    server: HomeServerOption,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (server.selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.35f),
        contentColor = if (server.selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = if (server.selected) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.subtitle.isNotBlank()) {
                    Text(
                        text = server.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RadioButton(
                selected = server.selected,
                onClick = if (enabled) onClick else null,
                enabled = enabled,
            )
        }
    }
}

/**
 * Maps the legacy 4-state [StatusOrbState] (kept on [HomeUiState] for back-compat) plus the
 * AUTO optimiser status and error flag into the new 7-phase orb visual model.
 *
 * The mapping is one-way and deliberately *richer* than the input — it surfaces the optimiser
 * progress and "unstable" cases that the old `StatusOrbState` enum couldn't express.
 *
 * Sprint 2 will lift this into `HomeUiState` directly so the mapper disappears.
 */
private fun mapToOrbPhase(state: HomeUiState): OrbPhase = when {
    state.errorMessage != null -> OrbPhase.Error
    state.optimizer is AutoOptimizerUiStatus.Probing -> OrbPhase.AutoOptimizing
    state.orb == StatusOrbState.Connecting -> OrbPhase.Connecting
    state.orb == StatusOrbState.Connected -> OrbPhase.Connected
    state.orb == StatusOrbState.Error -> OrbPhase.Error
    else -> OrbPhase.Idle
}

/**
 * Per-phase aurora intensity. Idle is dim; connected glows brighter; error tones down to keep
 * focus on the red orb without saturating the screen.
 */
private fun OrbPhase.auroraIntensity(): Float = when (this) {
    OrbPhase.Connected -> 1.0f
    OrbPhase.Connecting,
    OrbPhase.Validating,
    OrbPhase.Reconnecting,
    OrbPhase.AutoOptimizing -> 0.85f
    OrbPhase.Unstable -> 0.7f
    OrbPhase.Error -> 0.4f
    OrbPhase.Idle -> 0.55f
}
