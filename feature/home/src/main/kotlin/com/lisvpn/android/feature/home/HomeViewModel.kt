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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeVpnState: ObserveVpnStateUseCase,
    profileRepository: ProfileRepository,
    private val connectVpn: ConnectVpnUseCase,
    private val disconnectVpn: DisconnectVpnUseCase,
) : ViewModel() {

    private val activeProfile = profileRepository.observePrimaryProfile()
    private val activeServers = activeProfile.flatMapLatest { profile ->
        profile?.let { profileRepository.observeServers(it.id) } ?: flowOf(emptyList())
    }

    val uiState: StateFlow<HomeUiState> = combine(observeVpnState(), activeProfile, activeServers) { vpn, profile, servers ->
        HomeUiState.from(vpn, profile?.name, servers.size)
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
            when (val result = connectVpn(permission)) {
                is AppResult.Success -> Timber.d("connect dispatched")
                is AppResult.Failure -> Timber.w("connect failed: %s", result.error)
            }
            _isBusy.value = false
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

    private companion object {
        const val STATE_TIMEOUT_MS = 5_000L
    }
}
