package com.lisvpn.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.usecase.ConnectVpnUseCase
import com.lisvpn.android.core.domain.usecase.DisconnectVpnUseCase
import com.lisvpn.android.core.domain.usecase.ObserveVpnStateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeVpnState: ObserveVpnStateUseCase,
    profileRepository: ProfileRepository,
    private val connectVpn: ConnectVpnUseCase,
    private val disconnectVpn: DisconnectVpnUseCase,
) : ViewModel() {

    private val activeProfile = profileRepository.observePrimaryProfile()
    private val allServers = profileRepository.observeAllServers()
    private val connectionMode = MutableStateFlow(HomeConnectionMode.Auto)
    private val selectedServerId = MutableStateFlow<String?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        observeVpnState(),
        activeProfile,
        allServers,
        connectionMode,
        selectedServerId,
    ) { vpn, profile, servers, mode, selectedId ->
        HomeUiState.from(vpn, profile?.name, servers, mode, selectedId)
    }.combine(_statusMessage) { state, msg ->
        state.copy(statusMessage = msg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS),
        initialValue = HomeUiState.Empty,
    )

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    fun onConnectClick(permission: VpnPermissionHandle) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val mode = connectionMode.value
                _statusMessage.value = if (mode == HomeConnectionMode.Auto) {
                    "Проверяем серверы, выбираем лучший…"
                } else {
                    "Проверяем сервер…"
                }
                when (
                    val result = connectVpn(
                        permission = permission,
                        smartSelection = mode == HomeConnectionMode.Auto,
                        selectedServerId = selectedServerId.value.takeIf { mode == HomeConnectionMode.Manual },
                    )
                ) {
                    is AppResult.Success -> Timber.d("connect dispatched")
                    is AppResult.Failure -> Timber.w("connect failed: %s", result.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "connect crashed")
            } finally {
                _statusMessage.value = null
                _isBusy.value = false
            }
        }
    }

    fun onDisconnectClick() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            disconnectVpn()
            _isBusy.value = false
        }
    }

    fun onConnectionModeSelected(mode: HomeConnectionMode) {
        connectionMode.value = mode
        if (mode == HomeConnectionMode.Manual && selectedServerId.value == null) {
            selectedServerId.value = uiState.value.servers.firstOrNull()?.id
        }
    }

    fun onServerSelected(serverId: String) {
        selectedServerId.value = serverId
        connectionMode.value = HomeConnectionMode.Manual
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 5_000L
    }
}
