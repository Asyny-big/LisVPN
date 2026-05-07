package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Phase-1 stub. Real implementation (TCP ping + TLS handshake + 204 RTT) lands together
 * with WorkManager scheduling in `:vpn:health` Phase 5.
 */
@Singleton
class HealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun probe(server: Server): HealthSnapshot = withContext(ioDispatcher) {
        val networkType = currentNetworkType()
        val endpoint = InetSocketAddress(server.outbound.host, server.outbound.port)
        runCatching {
            Socket().use { socket ->
                val startedAt = SystemClock.elapsedRealtime()
                socket.tcpNoDelay = true
                socket.connect(endpoint, CONNECT_TIMEOUT_MS)
                val tcpMs = (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)
                Timber.i(
                    "Health probe success: server=%s endpoint=%s:%d tcpMs=%d network=%s",
                    server.displayName,
                    server.outbound.host,
                    server.outbound.port,
                    tcpMs,
                    networkType,
                )
                HealthSnapshot(
                    serverId = server.id,
                    timestamp = Clock.System.now(),
                    tcpHandshakeMs = tcpMs,
                    tlsHandshakeMs = null,
                    httpRttMs = null,
                    success = true,
                    networkType = networkType,
                )
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            Timber.w(
                "Health probe failed: server=%s endpoint=%s:%d network=%s reason=%s",
                server.displayName,
                server.outbound.host,
                server.outbound.port,
                networkType,
                err.healthProbeReason(),
            )
            HealthSnapshot(
                serverId = server.id,
                timestamp = Clock.System.now(),
                tcpHandshakeMs = null,
                tlsHandshakeMs = null,
                httpRttMs = null,
                success = false,
                networkType = networkType,
            )
        }
    }

    private fun currentNetworkType(): HealthSnapshot.NetworkType {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return HealthSnapshot.NetworkType.Unknown
        val caps = manager.getNetworkCapabilities(network) ?: return HealthSnapshot.NetworkType.Unknown
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> HealthSnapshot.NetworkType.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> HealthSnapshot.NetworkType.Cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> HealthSnapshot.NetworkType.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> HealthSnapshot.NetworkType.VpnInterface
            else -> HealthSnapshot.NetworkType.Unknown
        }
    }

    private fun Throwable.healthProbeReason(): String =
        "${this::class.java.simpleName}: ${message.orEmpty()}"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_500
    }
}
