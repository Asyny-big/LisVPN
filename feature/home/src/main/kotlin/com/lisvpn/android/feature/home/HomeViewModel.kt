package com.lisvpn.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerStatus
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeVpnState: ObserveVpnStateUseCase,
    profileRepository: ProfileRepository,
    autoOptimizer: AutoOptimizerRepository,
    private val serverHealthRepository: ServerHealthRepository,
    private val connectVpn: ConnectVpnUseCase,
    private val disconnectVpn: DisconnectVpnUseCase,
) : ViewModel() {

    private val activeProfile = profileRepository.observePrimaryProfile()
    private val allServers = profileRepository.observeAllServers()
    private val connectionMode = MutableStateFlow(HomeConnectionMode.Auto)
    private val selectedServerId = MutableStateFlow<String?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val manualChecks = MutableStateFlow<Map<String, ManualServerCheckUi>>(emptyMap())
    private var manualProbeJob: Job? = null
    private val optimizerStatus = autoOptimizer.status

    val uiState: StateFlow<HomeUiState> = combine(
        observeVpnState(),
        activeProfile,
        allServers,
        connectionMode,
        selectedServerId,
    ) { vpn, profile, servers, mode, selectedId ->
        HomeInputs(vpn, profile?.name, servers, mode, selectedId)
    }.combine(manualChecks) { inputs, checks ->
        HomeUiState.from(
            vpn = inputs.vpn,
            profileName = inputs.profileName,
            allServers = inputs.servers,
            connectionMode = inputs.mode,
            selectedServerId = inputs.selectedId,
            manualChecks = checks,
        )
    }.combine(_statusMessage) { state, msg ->
        state.copy(statusMessage = msg)
    }.combine(optimizerStatus) { state, optimizer ->
        state.withOptimizerStatus(optimizer.toUiStatus())
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
        if (mode == HomeConnectionMode.Manual) {
            val manualServerIds = uiState.value.servers.map { it.id }.toSet()
            val selectedId = selectedServerId.value
            if (selectedId == null || selectedId !in manualServerIds) {
                selectedServerId.value = uiState.value.servers.firstOrNull()?.id
            }
        } else {
            manualProbeJob?.cancel()
        }
    }

    fun onServerSelected(serverId: String) {
        selectedServerId.value = serverId
        connectionMode.value = HomeConnectionMode.Manual
    }

    fun onCheckServersClick() {
        refreshManualServerChecks()
    }

    private fun refreshManualServerChecks() {
        manualProbeJob?.cancel()
        manualProbeJob = viewModelScope.launch {
            val targets = allServers.first()
            if (targets.isEmpty()) {
                manualChecks.value = emptyMap()
                return@launch
            }
            manualChecks.value = targets.associate { server -> server.id to ManualServerCheckUi(checking = true) }
            val semaphore = Semaphore(MANUAL_CHECK_PARALLELISM)
            targets.map { server ->
                launch {
                    semaphore.withPermit {
                        val result = serverHealthRepository.quickProbe(server)
                        val snapshot = (result as? AppResult.Success)?.value
                        manualChecks.value = manualChecks.value + (
                            server.id to ManualServerCheckUi(
                                checking = false,
                                reachable = snapshot?.success == true,
                                pingMs = snapshot?.tcpHandshakeMs,
                            )
                        )
                    }
                }
            }.joinAll()
        }
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 5_000L
        const val MANUAL_CHECK_PARALLELISM = 6
    }
}

private data class HomeInputs(
    val vpn: com.lisvpn.android.core.domain.model.VpnState,
    val profileName: String?,
    val servers: List<com.lisvpn.android.core.domain.model.Server>,
    val mode: HomeConnectionMode,
    val selectedId: String?,
)

private fun AutoOptimizerStatus.toUiStatus(): AutoOptimizerUiStatus = when (this) {
    is AutoOptimizerStatus.Idle -> AutoOptimizerUiStatus.Idle
    is AutoOptimizerStatus.Probing -> AutoOptimizerUiStatus.Probing(
        current = current,
        total = total,
        serverDisplayName = serverDisplayName,
        lastSpeedKbps = lastSpeedKbps,
        lastServerDisplayName = lastServerDisplayName,
        stage = stage,
        stageMessage = stageMessage,
        progressPercent = progressPercent,
        estimatedRemainingMs = estimatedRemainingMs,
        checked = checked,
        reachable = reachable,
        debugSummary = debugSummary,
    )
    is AutoOptimizerStatus.Done -> AutoOptimizerUiStatus.Done(
        bestServerDisplayName = bestServerDisplayName,
        bestSpeedKbps = bestSpeedKbps,
        tested = tested,
        total = total,
        elapsedMs = elapsedMs,
        selectionReason = selectionReason,
        debugSummary = debugSummary,
    )
    is AutoOptimizerStatus.Failed -> AutoOptimizerUiStatus.Failed(
        reason = reason,
        stage = stage,
        tested = tested,
        total = total,
        debugSummary = debugSummary,
    )
}
