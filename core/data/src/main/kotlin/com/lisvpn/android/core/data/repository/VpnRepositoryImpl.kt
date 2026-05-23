package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.repository.VpnRepository
import com.lisvpn.android.vpn.core.VpnConnectionController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Domain-facing facade. Reads current per-app rules and delegates the actual VPN runtime to
 * [VpnConnectionController] (which in turn fires Intents at [com.lisvpn.android.vpn.core.LisVpnService]).
 */
@Singleton
class VpnRepositoryImpl @Inject constructor(
    private val controller: VpnConnectionController,
    private val appRulesRepository: AppRulesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VpnRepository {

    override val state: StateFlow<VpnState> get() = controller.state

    override suspend fun start(
        servers: List<Server>,
        smartSelection: Boolean,
        permission: VpnPermissionHandle,
    ): AppResult<Unit> {
        val rules = runCatching { appRulesRepository.observe().first() }.getOrDefault(AppRules.Default)
        // AUTO must not block on Android's OS resolver: captive portals, broken carrier DNS and
        // DPI can make InetAddress.getAllByName() wait for seconds before the VPN service even
        // starts. The sing-box config already routes server-domain DNS to direct DoH, and
        // LisVpnService runs its own bounded fast filter. Keep pre-resolve only for manual mode,
        // where a single user-selected hostname benefits from the legacy IPv4 workaround.
        val resolvedServers = withContext(ioDispatcher) {
            servers.mapIndexed { index, server ->
                if (!smartSelection && index == 0) server.withResolvedHost() else server
            }
        }
        return controller.start(
            servers = resolvedServers,
            smartSelection = smartSelection,
            appRules = rules,
            permission = permission,
        ).fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Vpn(it.message ?: "start failed"), it) },
        )
    }

    override suspend fun stop(): AppResult<Unit> {
        return controller.stop().fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Vpn(it.message ?: "stop failed"), it) },
        )
    }

    override suspend fun selectOutbound(groupTag: String, outboundTag: String): AppResult<Unit> {
        return controller.selectOutbound(groupTag, outboundTag).fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Vpn(it.message ?: "select outbound failed"), it) },
        )
    }

    override fun acknowledgeError() = controller.acknowledgeError()

    private fun Server.withResolvedHost(): Server {
        val originalHost = outbound.host.trim().removeSuffix(".")
        if (!originalHost.needsResolution()) return this
        val resolvedHost = runCatching {
            InetAddress.getAllByName(originalHost)
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
        if (resolvedHost.isNullOrBlank()) {
            Timber.w("Server host pre-resolve failed: server=%s host=%s", displayName, originalHost)
            return this
        }
        Timber.i("Server host pre-resolved: server=%s host=%s ip=%s", displayName, originalHost, resolvedHost)
        return copy(outbound = outbound.withDialHost(resolvedHost, originalHost))
    }

    private fun Outbound.withDialHost(resolvedHost: String, originalHost: String): Outbound = when (this) {
        is Outbound.Vless -> copy(
            host = resolvedHost,
            security = security.withDefaultServerName(originalHost),
            transport = transport.withDefaultHost(originalHost),
        )
        is Outbound.Vmess -> copy(
            host = resolvedHost,
            security = security.withDefaultServerName(originalHost),
            transport = transport.withDefaultHost(originalHost),
        )
        is Outbound.Trojan -> copy(
            host = resolvedHost,
            security = security.withDefaultServerName(originalHost),
            transport = transport.withDefaultHost(originalHost),
        )
        is Outbound.Shadowsocks -> copy(host = resolvedHost)
    }

    private fun Security.withDefaultServerName(originalHost: String): Security = when (this) {
        Security.None -> this
        is Security.Tls -> if (sni.isNullOrBlank()) copy(sni = originalHost) else this
        is Security.Reality -> this
    }

    private fun Transport.withDefaultHost(originalHost: String): Transport = when (this) {
        Transport.Tcp -> this
        is Transport.WebSocket -> if (host.isNullOrBlank()) copy(host = originalHost) else this
        is Transport.Grpc -> this
        is Transport.HttpUpgrade -> if (host.isNullOrBlank()) copy(host = originalHost) else this
        is Transport.XHttp -> if (host.isNullOrBlank()) copy(host = originalHost) else this
    }

    private fun String.needsResolution(): Boolean =
        !contains(':') && any { it.isLetter() }
}
