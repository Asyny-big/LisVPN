package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.core.domain.model.isGeneralVpnEligible
import com.lisvpn.android.core.domain.model.isVlessFlowSupportedByCurrentLibbox
import com.lisvpn.android.core.domain.model.specialPurposeReason
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber

/**
 * Orchestrates the "Connect" tap:
 *  1. Pull the active profile.
 *  2. In AUTO, pick a stable bootstrap order and optimize inside the running tunnel.
 *  3. Delegate to [VpnRepository.start] with a permission handle supplied by the activity.
 */
class ConnectVpnUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val selectBestServer: SelectBestServerUseCase,
    private val serverHealthRepository: ServerHealthRepository,
    private val autoOptimizerRepository: AutoOptimizerRepository,
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
            val compatible = servers.filter { it.isSupportedByCurrentLibbox() }
            val generalCandidates = compatible.filter { it.isGeneralVpnEligible() }
            val excludedSpecial = compatible.size - generalCandidates.size
            Timber.i(
                "Auto connect pipeline: before scoring available=%d compatible=%d general=%d excludedSpecial=%d",
                servers.size,
                compatible.size,
                generalCandidates.size,
                excludedSpecial,
            )
            if (compatible.isEmpty()) {
                return AppResult.Failure(AppError.Vpn("No sing-box compatible VPN servers in subscription"))
            }
            if (generalCandidates.isEmpty()) {
                return AppResult.Failure(AppError.Vpn("No general-purpose VPN servers in subscription"))
            }
            val ranked = selectBestServer(generalCandidates, limit = SMART_LIMIT)
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
            val specialReason = server.specialPurposeReason()
            if (specialReason != null) {
                Timber.w(
                    "Manual VPN selection rejected: server=%s reason=%s",
                    server.displayName,
                    specialReason,
                )
                return AppResult.Failure(AppError.Vpn("Selected server is $specialReason and cannot be used as a general VPN"))
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
        val result = vpnRepository.start(
            servers = selected,
            smartSelection = smartSelection,
            permission = permission,
        )
        if (result is AppResult.Success) {
            // The previous behaviour (schedule the in-tunnel optimizer here) was exactly the
            // "speed test runs after the VPN is already connected" surprise the user reported.
            // The pre-VPN preflight in LisVpnService is the canonical AUTO speed-test now, so
            // we deliberately do NOT schedule the post-connect optimizer in smart mode anymore.
            // We still cancel any leftover optimizer job from a previous session.
            autoOptimizerRepository.cancel()
        }
        return result
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
        const val SMART_LIMIT = 32
        val UNSUPPORTED_XRAY_HTTP_TRANSPORT_REGEX = Regex("([?&])type=(xhttp|splithttp)(&|#|$)", RegexOption.IGNORE_CASE)
    }
}
