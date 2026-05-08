package com.lisvpn.android.vpn.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.ConnectedServer
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import timber.log.Timber

@Singleton
class VpnConnectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configAssembler: VpnConfigAssembler,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    suspend fun start(
        servers: List<Server>,
        smartSelection: Boolean,
        appRules: AppRules,
        permission: VpnPermissionHandle,
    ): Result<Unit> = mutex.withLock {
        if (servers.isEmpty()) return Result.failure(IllegalArgumentException("No servers provided"))
        if (_state.value is VpnState.Preparing || _state.value is VpnState.Connecting || _state.value is VpnState.Disconnecting) {
            return Result.failure(IllegalStateException("VPN transition is already in progress"))
        }

        _state.value = VpnState.Preparing
        val granted = runCatching { permission.ensureGranted() }.getOrDefault(false)
        if (!granted) {
            _state.value = VpnState.Error(VpnState.Reason.PermissionDenied)
            return Result.failure(SecurityException("VpnService permission denied"))
        }

        Timber.i(
            "VPN config assembly requested: servers=%d smart=%s candidates=%s",
            servers.size,
            smartSelection,
            servers.joinToString { it.diagnosticLabel() },
        )
        val configJson = runCatching { configAssembler.assemble(servers, smartSelection, appRules) }
            .onFailure { err ->
                Timber.e(err, "VPN config assembly failed")
                _state.value = VpnState.Error(VpnState.Reason.ConfigInvalid, err.message)
            }
            .getOrElse { return Result.failure(it) }
        val displayName = if (smartSelection && servers.size > 1) {
            "Авто · ${servers.size} кандидатов"
        } else {
            servers.firstOrNull()?.displayName
        }

        _state.value = VpnState.Connecting(serverDisplayName = displayName)
        Timber.i(
            "VPN start requested: servers=%d smart=%s configBytes=%d candidates=%s",
            servers.size,
            smartSelection,
            configJson.length,
            servers.joinToString { it.diagnosticLabel() },
        )

        runCatching {
            val intent = Intent(context, LisVpnService::class.java).apply {
                action = VpnIntents.ACTION_START
                putExtra(VpnIntents.EXTRA_CONFIG_JSON, configJson)
                putExtra(VpnIntents.EXTRA_SERVER_LABEL, displayName)
            }
            startServiceCompat(intent)
        }.onFailure { err ->
            Timber.e(err, "VpnConnectionController.start failed")
            _state.value = VpnState.Error(VpnState.Reason.StartFailed, err.message)
        }
    }

    suspend fun stop(): Result<Unit> = mutex.withLock {
        if (_state.value == VpnState.Idle) return Result.success(Unit)
        _state.value = VpnState.Disconnecting
        Timber.i("VPN stop requested")
        runCatching {
            val intent = Intent(context, LisVpnService::class.java).apply {
                action = VpnIntents.ACTION_STOP
            }
            context.startService(intent)
            Unit
        }.onFailure { Timber.e(it, "VpnConnectionController.stop failed") }
    }

    suspend fun reconnect(): Result<Unit> = mutex.withLock {
        val current = _state.value
        if (current !is VpnState.Connected && current !is VpnState.Reconnecting) {
            return Result.failure(IllegalStateException("VPN is not connected"))
        }
        val attempt = if (current is VpnState.Reconnecting) current.attempt + 1 else 1
        publishReconnecting(attempt)
        runCatching {
            val intent = Intent(context, LisVpnService::class.java).apply { action = VpnIntents.ACTION_RECONNECT }
            context.startService(intent)
            Unit
        }.onFailure { Timber.e(it, "VpnConnectionController.reconnect failed") }
    }

    suspend fun selectOutbound(groupTag: String, outboundTag: String): Result<Unit> = mutex.withLock {
        val current = _state.value
        if (current !is VpnState.Connected && current !is VpnState.Reconnecting) {
            return Result.failure(IllegalStateException("VPN is not connected"))
        }
        Timber.i("VPN outbound switch requested: group=%s outbound=%s", groupTag, outboundTag)
        runCatching {
            val intent = Intent(context, LisVpnService::class.java).apply {
                action = VpnIntents.ACTION_SELECT_OUTBOUND
                putExtra(VpnIntents.EXTRA_OUTBOUND_GROUP, groupTag)
                putExtra(VpnIntents.EXTRA_OUTBOUND_TAG, outboundTag)
            }
            context.startService(intent)
            Unit
        }.onFailure { Timber.e(it, "VpnConnectionController.selectOutbound failed") }
    }

    fun acknowledgeError() {
        _state.value = VpnState.Idle
    }

    internal fun publishConnected(server: ConnectedServer, connectedAt: Instant) {
        _state.value = VpnState.Connected(server = server, connectedAt = connectedAt)
    }

    internal fun publishReconnecting(attempt: Int) {
        val previous = when (val state = _state.value) {
            is VpnState.Connected -> state.server.displayName
            is VpnState.Reconnecting -> state.previousServerDisplayName
            else -> null
        }
        _state.value = VpnState.Reconnecting(attempt = attempt, previousServerDisplayName = previous)
    }

    internal fun publishError(reason: VpnState.Reason, detail: String? = null) {
        _state.value = VpnState.Error(reason, detail)
    }

    internal fun publishIdle() {
        _state.value = VpnState.Idle
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun preparePermissionIntent(): Intent? = VpnService.prepare(context)

    private fun Server.diagnosticLabel(): String =
        "$displayName/${outbound.protocol}/${outbound.host}:${outbound.port}"
}
