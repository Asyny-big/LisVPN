package com.lisvpn.android.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Public extension that the app navigation graph composes in. Keeps `:app` ignorant of
 * the Composable internals.
 */
fun NavGraphBuilder.homeRoute(
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToImport: () -> Unit,
) {
    composable("home") {
        HomeRoute(
            onNavigateToServers = onNavigateToServers,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToProfiles = onNavigateToProfiles,
            onNavigateToImport = onNavigateToImport,
        )
    }
}

@Composable
private fun HomeRoute(
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToImport: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val permissionHandle = rememberVpnPermissionHandle()

    HomeScreen(
        state = state,
        isBusy = isBusy,
        onPrimaryAction = {
            if (state.canDisconnect) viewModel.onDisconnectClick()
            else if (state.canConnect) viewModel.onConnectClick(permissionHandle)
        },
        onNavigateToServers = onNavigateToServers,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToProfiles = if (state.showImportPrompt) onNavigateToImport else onNavigateToProfiles,
    )
}
