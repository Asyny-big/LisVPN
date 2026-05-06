package com.lisvpn.android.core.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Convenience wrapper that defaults to [Lifecycle.State.STARTED] collection — preferred default
 * for VPN-related state to avoid wasting work while UI is hidden.
 */
@Composable
fun <T> StateFlow<T>.collectAsLisState(): State<T> = collectAsStateWithLifecycle(minActiveState = Lifecycle.State.STARTED)
