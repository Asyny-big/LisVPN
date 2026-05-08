package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.model.isVlessFlowSupportedByCurrentLibbox
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Orchestrates the "Connect" tap:
 *  1. Pull the active profile.
 *  2. Ask [SelectBestServerUseCase] for a ranked subset (urltest will further refine in real time).
 *  3. Delegate to [VpnRepository.start] with a permission handle supplied by the activity.
 */
class ConnectVpnUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val selectBestServer: SelectBestServerUseCase,
    private val serverHealthRepository: ServerHealthRepository,
    private val vpnRepository: VpnRepository,
) {
    suspend operator fun invoke(
        permission: VpnPermissionHandle,
        smartSelection: Boolean = true,
        selectedServerId: String? = null,
    ): AppResult<Unit> {
        val servers = profileRepository.observeAllServers().firstOrNull().orEmpty()
        if (servers.isEmpty()) return AppResult.Failure(AppError.Vpn("No servers available"))

        val selected = if (smartSelection) {
            val stopResult = stopActiveVpnBeforeAutoSpeedTest()
            if (stopResult != null) return stopResult
            val compatible = servers.filter { it.isSupportedByCurrentLibbox() }
            Timber.i(
                "Auto connect pipeline: before scoring available=%d compatible=%d",
                servers.size,
                compatible.size,
            )
            if (compatible.isEmpty()) {
                return AppResult.Failure(AppError.Vpn("No sing-box compatible VPN servers in subscription"))
            }
            val ranked = selectBestServer(compatible, limit = SMART_LIMIT)
            Timber.i(
                "Auto connect pipeline: after scoring selectedCount=%d selected=%s",
                ranked.size,
                ranked.joinToString { it.displayName },
            )
            ranked
        } else {
            val serverId = selectedServerId
                ?: return AppResult.Failure(AppError.Vpn("Select a server first"))
            val server = servers.firstOrNull { it.id == serverId }
                ?: return AppResult.Failure(AppError.Vpn("Selected server is not available"))
            if (!server.isSupportedByCurrentLibbox()) {
                return AppResult.Failure(AppError.Vpn("Selected server uses unsupported VLESS transport"))
            }
            val snapshot = when (val probeResult = serverHealthRepository.probe(server)) {
                is AppResult.Success -> probeResult.value
                is AppResult.Failure -> return AppResult.Failure(
                    AppError.Vpn("Selected server is not reachable on current network"),
                    probeResult.cause,
                )
            }
            if (!snapshot.success) {
                return AppResult.Failure(AppError.Vpn("Selected server TCP is reachable, but VPN tunnel validation failed"))
            }
            listOfNotNull(server)
        }
        Timber.i(
            "Connect VPN requested: mode=%s available=%d selected=%s",
            if (smartSelection) "auto" else "manual",
            servers.size,
            selected.joinToString { it.displayName },
        )
        if (selected.isEmpty()) return AppResult.Failure(AppError.Vpn("No reachable VPN servers on current network"))
        Timber.i(
            "Auto connect pipeline: before connect smart=%s selectedCount=%d",
            smartSelection,
            selected.size,
        )
        return vpnRepository.start(
            servers = selected,
            smartSelection = smartSelection,
            permission = permission,
        )
    }

    private suspend fun stopActiveVpnBeforeAutoSpeedTest(): AppResult<Unit>? {
        return when (vpnRepository.state.value) {
            is VpnState.Connected,
            is VpnState.Reconnecting -> {
                Timber.i("Auto speed-test requested while VPN is active; stopping current tunnel before probing")
                when (val stop = vpnRepository.stop()) {
                    is AppResult.Failure -> return AppResult.Failure(AppError.Vpn("Could not stop active VPN before AUTO speed test"), stop.cause)
                    is AppResult.Success -> Unit
                }
                val stopped = withTimeoutOrNull(AUTO_STOP_TIMEOUT_MS) {
                    vpnRepository.state.firstOrNull { state -> state is VpnState.Idle || state is VpnState.Error }
                } != null
                if (!stopped) {
                    AppResult.Failure(AppError.Vpn("Timed out stopping active VPN before AUTO speed test"))
                } else {
                    null
                }
            }
            VpnState.Preparing,
            is VpnState.Connecting,
            VpnState.Disconnecting -> AppResult.Failure(AppError.Vpn("VPN transition is already in progress"))
            VpnState.Idle,
            is VpnState.Error -> null
        }
    }

    private fun Server.isSupportedByCurrentLibbox(): Boolean =
        !rawUri.hasUnsupportedXrayHttpTransport() && outbound.isSupportedByCurrentLibbox()

    private fun Outbound.isSupportedByCurrentLibbox(): Boolean = when (this) {
        is Outbound.Vless -> isVlessFlowSupportedByCurrentLibbox(flow) && transport.isSupportedByCurrentLibbox() && security.isSupportedByCurrentLibbox()
        is Outbound.Vmess -> transport.isSupportedByCurrentLibbox() && security.isSupportedByCurrentLibbox()
        is Outbound.Trojan -> transport.isSupportedByCurrentLibbox() && security.isSupportedByCurrentLibbox()
        is Outbound.Shadowsocks -> true
    }

    private fun Transport.isSupportedByCurrentLibbox(): Boolean = when (this) {
        Transport.Tcp,
        is Transport.WebSocket,
        is Transport.Grpc,
        is Transport.HttpUpgrade,
        is Transport.XHttp,
        -> true
    }

    private fun Security.isSupportedByCurrentLibbox(): Boolean = true

    private fun String.hasUnsupportedXrayHttpTransport(): Boolean =
        UNSUPPORTED_XRAY_HTTP_TRANSPORT_REGEX.containsMatchIn(this)

    private companion object {
        const val SMART_LIMIT = 2_000
        const val AUTO_STOP_TIMEOUT_MS = 5_000L
        val UNSUPPORTED_XRAY_HTTP_TRANSPORT_REGEX = Regex("([?&])type=(xhttp|splithttp)(&|#|$)", RegexOption.IGNORE_CASE)
    }
}
