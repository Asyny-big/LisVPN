package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.os.Build
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@Singleton
class FastProbeWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun probeAll(
        servers: List<TaggedServer>,
        onProgress: (completed: Int, total: Int, reachable: Int, result: FastProbeResult) -> Unit = { _, _, _, _ -> },
    ): List<FastProbeResult> = withContext(ioDispatcher) {
        // Mobile carriers (especially Russian ones on a whitelisted IP plan) tend to NAT-collapse
        // or throttle bursts of parallel TLS handshakes from the same device. We therefore use a
        // lower worker-pool and shorter per-candidate budgets on cellular: dead routes fail in
        // ~1.5s, while healthy TCP/REALITY endpoints usually return in a single RTT.
        val budget = if (isMobileLikeNetwork()) FastProbeBudget.Mobile else FastProbeBudget.Fixed
        val semaphore = Semaphore(budget.parallelism)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val reachable = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            servers.map { tagged ->
                async {
                    val result = semaphore.withPermit {
                        withTimeoutOrNull(budget.totalTimeoutMs) {
                            probe(tagged, budget)
                        } ?: FastProbeResult(
                            taggedServer = tagged,
                            dnsMs = null,
                            tcpMs = null,
                            tlsMs = null,
                            proxyHandshakeMs = null,
                            success = false,
                            failureReason = "timeout",
                        )
                    }
                    val done = completed.incrementAndGet()
                    val ok = if (result.success) reachable.incrementAndGet() else reachable.get()
                    onProgress(done, servers.size, ok, result)
                    result
                }
            }.awaitAll()
        }
    }

    private fun probe(tagged: TaggedServer, budget: FastProbeBudget): FastProbeResult {
        val server = tagged.server
        val startedAt = SystemClock.elapsedRealtime()
        val host = server.outbound.host.trim().removeSuffix(".")
        val port = server.outbound.port
        if (host.isBlank() || port <= 0) {
            return failed(tagged, null, null, null, "invalid endpoint")
        }

        val dnsStartedAt = SystemClock.elapsedRealtime()
        val addresses = runCatching {
            InetAddress.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .ifEmpty { InetAddress.getAllByName(host).toList() }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            return failed(tagged, elapsedSince(dnsStartedAt), null, null, "dns: ${err.shortReason()}")
        }
        val dnsMs = elapsedSince(dnsStartedAt)
        if (addresses.isEmpty()) return failed(tagged, dnsMs, null, null, "dns: empty")

        var lastFailure = "connect failed"
        for (address in addresses) {
            val tcpStartedAt = SystemClock.elapsedRealtime()
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = budget.socketReadTimeoutMs
                socket.connect(InetSocketAddress(address, port), budget.tcpConnectTimeoutMs)
                val tcpMs = elapsedSince(tcpStartedAt)
                val tlsMs = measureTlsIfNeeded(socket, host, server, budget)
                if (tlsMs == TLS_FAILED) {
                    return failed(tagged, dnsMs, tcpMs, null, "tls handshake failed")
                }
                val handshakeMs = elapsedSince(startedAt)
                return FastProbeResult(
                    taggedServer = tagged,
                    dnsMs = dnsMs,
                    tcpMs = tcpMs,
                    tlsMs = tlsMs.takeIf { it >= 0 },
                    proxyHandshakeMs = handshakeMs,
                    success = true,
                    failureReason = null,
                )
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                lastFailure = err.shortReason()
            } finally {
                runCatching { socket.close() }
            }
        }
        Timber.d(
            "Fast probe failed: server=%s endpoint=%s:%d reason=%s",
            server.displayName,
            host,
            port,
            lastFailure,
        )
        return failed(tagged, dnsMs, null, null, lastFailure)
    }

    private fun measureTlsIfNeeded(socket: Socket, host: String, server: Server, budget: FastProbeBudget): Int {
        val security = server.outbound.security()
        if (security == Security.None) return TLS_NOT_REQUIRED
        if (security is Security.Reality) return TLS_NOT_REQUIRED
        val tls = security as? Security.Tls ?: return TLS_NOT_REQUIRED
        if (tls.allowInsecure) return TLS_NOT_REQUIRED
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, server.outbound.port, false) as SSLSocket
            ssl.use {
                it.soTimeout = budget.socketReadTimeoutMs
                it.sslParameters = SSLParameters().apply {
                    serverNames = listOf(SNIHostName(tls.sni ?: host))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tls.alpn.isNotEmpty()) {
                        applicationProtocols = tls.alpn.toTypedArray()
                    }
                }
                it.startHandshake()
            }
            elapsedSince(startedAt)
        }.getOrElse { TLS_FAILED }
    }

    private fun failed(
        tagged: TaggedServer,
        dnsMs: Int?,
        tcpMs: Int?,
        tlsMs: Int?,
        reason: String,
    ): FastProbeResult = FastProbeResult(
        taggedServer = tagged,
        dnsMs = dnsMs,
        tcpMs = tcpMs,
        tlsMs = tlsMs,
        proxyHandshakeMs = null,
        success = false,
        failureReason = reason,
    )

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private fun Throwable.shortReason(): String =
        "${this::class.java.simpleName}: ${message.orEmpty()}".take(96)

    private fun isMobileLikeNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // If the VPN we're probing for is already up (rare during fast probe but possible on
            // reconnect), fall back to scanning the other networks to figure out the underlying
            // transport.
            return manager.allNetworks
                .asSequence()
                .mapNotNull(manager::getNetworkCapabilities)
                .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                .any { it.isMobileLike() }
        }
        return caps.isMobileLike()
    }

    private fun NetworkCapabilities.isMobileLike(): Boolean {
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return false
        }
        // Metered without an explicit known transport — treat as mobile-like.
        return !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private data class FastProbeBudget(
        val parallelism: Int,
        val totalTimeoutMs: Long,
        val tcpConnectTimeoutMs: Int,
        val socketReadTimeoutMs: Int,
    ) {
        companion object {
            val Fixed = FastProbeBudget(
                parallelism = 10,
                totalTimeoutMs = 1_500L,
                tcpConnectTimeoutMs = 1_000,
                socketReadTimeoutMs = 1_000,
            )
            val Mobile = FastProbeBudget(
                parallelism = 4,
                totalTimeoutMs = 1_800L,
                tcpConnectTimeoutMs = 1_300,
                socketReadTimeoutMs = 1_300,
            )
        }
    }

    private companion object {
        const val TLS_NOT_REQUIRED = -1
        const val TLS_FAILED = -2
    }
}

private fun com.lisvpn.android.core.domain.model.Outbound.security(): Security = when (this) {
    is com.lisvpn.android.core.domain.model.Outbound.Vless -> security
    is com.lisvpn.android.core.domain.model.Outbound.Vmess -> security
    is com.lisvpn.android.core.domain.model.Outbound.Trojan -> security
    is com.lisvpn.android.core.domain.model.Outbound.Shadowsocks -> Security.None
}
