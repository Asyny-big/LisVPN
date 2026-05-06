package com.lisvpn.android.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(48.dp))

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

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LisPrimaryButton(
                        text = if (state.canDisconnect) "Отключить" else "Подключить",
                        onClick = onPrimaryAction,
                        enabled = state.canConnect || state.canDisconnect,
                        loading = isBusy,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.TextButton(onClick = onNavigateToProfiles) {
                        Text("Профили")
                    }
                }
            }
        }
    }
}
