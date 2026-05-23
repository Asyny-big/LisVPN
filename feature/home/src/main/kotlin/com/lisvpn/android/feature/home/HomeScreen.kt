package com.lisvpn.android.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lisvpn.android.core.designsystem.component.LisInfoLine
import com.lisvpn.android.core.designsystem.component.LisPrimaryButton
import com.lisvpn.android.core.designsystem.component.StatusOrb

/**
 * Home — the only screen most users ever see. Hierarchy:
 *  TopBar (brand + settings)
 *  Center column:
 *    StatusOrb (the hero)
 *    title (state)
 *    subtitle (server / hint)
 *  Bottom column:
 *    primary CTA (Connect / Disconnect)
 *    secondary action (server picker / import) — when relevant
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onConnectionModeSelected: (HomeConnectionMode) -> Unit,
    onServerSelected: (String) -> Unit,
    onCheckServersClick: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        HomeBody(
            state = state,
            isBusy = isBusy,
            onPrimaryAction = onPrimaryAction,
            onConnectionModeSelected = onConnectionModeSelected,
            onServerSelected = onServerSelected,
            onCheckServersClick = onCheckServersClick,
            onNavigateToProfiles = onNavigateToProfiles,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun HomeBody(
    state: HomeUiState,
    isBusy: Boolean,
    onPrimaryAction: () -> Unit,
    onConnectionModeSelected: (HomeConnectionMode) -> Unit,
    onServerSelected: (String) -> Unit,
    onCheckServersClick: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            StatusOrb(
                state = state.orb,
                onTap = onPrimaryAction,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            state.subtitle?.let {
                LisInfoLine(leading = it, trailing = null)
            }
        }

        AnimatedVisibility(visible = state.autoProgress != null) {
            state.autoProgress?.let { progress ->
                AutoProgressCard(progress = progress)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = state.showImportPrompt) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LisPrimaryButton(
                        text = "Импортировать подписку",
                        onClick = onNavigateToProfiles,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            AnimatedVisibility(visible = !state.showImportPrompt) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectionModeSwitch(
                        selected = state.connectionMode,
                        enabled = !isBusy && state.canConnect,
                        onSelected = onConnectionModeSelected,
                    )
                    LisPrimaryButton(
                        text = if (state.canDisconnect) "Отключить" else "Подключить",
                        onClick = onPrimaryAction,
                        enabled = state.canConnect || state.canDisconnect,
                        loading = isBusy && !state.canDisconnect,
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
                    AnimatedVisibility(visible = state.connectionMode == HomeConnectionMode.Manual) {
                        ManualServerPicker(
                            servers = state.servers,
                            enabled = !isBusy && state.canConnect,
                            checking = state.manualCheckInProgress,
                            onCheckServersClick = onCheckServersClick,
                            onServerSelected = onServerSelected,
                        )
                    }
                    TextButton(onClick = onNavigateToProfiles) {
                        Text("Профили")
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AutoProgressCard(progress: HomeAutoProgressUi) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when (progress.severity) {
        HomeAutoProgressUi.Severity.Info -> colors.surfaceVariant
        HomeAutoProgressUi.Severity.Success -> colors.primaryContainer
        HomeAutoProgressUi.Severity.Warning -> colors.tertiaryContainer
        HomeAutoProgressUi.Severity.Error -> colors.errorContainer
    }
    val contentColor = when (progress.severity) {
        HomeAutoProgressUi.Severity.Info -> colors.onSurfaceVariant
        HomeAutoProgressUi.Severity.Success -> colors.onPrimaryContainer
        HomeAutoProgressUi.Severity.Warning -> colors.onTertiaryContainer
        HomeAutoProgressUi.Severity.Error -> colors.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = progress.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            progress.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            progress.currentServer?.let {
                Text(
                    text = "Сейчас: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress.progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progress.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (progress.severity == HomeAutoProgressUi.Severity.Info || progress.severity == HomeAutoProgressUi.Severity.Warning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val meta = listOfNotNull(progress.progressText, progress.etaText).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            progress.resultText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            progress.debugText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
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
    checking: Boolean,
    onCheckServersClick: () -> Unit,
    onServerSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Выберите сервер",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onCheckServersClick,
                enabled = enabled && !checking,
            ) {
                Text(if (checking) "Проверяем…" else "Проверить все")
            }
        }
        servers.forEach { server ->
            ManualServerCard(
                server = server,
                enabled = enabled,
                onClick = { onServerSelected(server.id) },
            )
        }
    }
}

@Composable
private fun ManualServerCheckUi.statusColor(): Color = when {
    checking -> MaterialTheme.colorScheme.primary
    reachable == true -> MaterialTheme.colorScheme.primary
    reachable == false -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun ManualServerCheckUi.statusMark(): String = when {
    checking -> "…"
    reachable == true -> "●"
    reachable == false -> "⚠"
    else -> "○"
}

@Composable
private fun ManualServerCard(
    server: HomeServerOption,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (server.selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (server.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                server.check?.let { check ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = check.statusMark(),
                            style = MaterialTheme.typography.bodySmall,
                            color = check.statusColor(),
                        )
                        Text(
                            text = check.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = check.statusColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
