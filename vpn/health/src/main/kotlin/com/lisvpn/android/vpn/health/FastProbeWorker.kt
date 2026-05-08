package com.lisvpn.android.vpn.health

import android.os.SystemClock
import android.os.Build
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun probeAll(servers: List<TaggedServer>): List<FastProbeResult> = withContext(ioDispatcher) {
        val semaphore = Semaphore(FAST_PROBE_PARALLELISM)
        coroutineScope {
            servers.map { tagged ->
                async {
                    semaphore.withPermit {
                        withTimeoutOrNull(FAST_PROBE_TOTAL_TIMEOUT_MS) {
                            probe(tagged)
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
                }
            }.awaitAll()
        }
    }

    private fun probe(tagged: TaggedServer): FastProbeResult {
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
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(address, port), TCP_CONNECT_TIMEOUT_MS)
                val tcpMs = elapsedSince(tcpStartedAt)
                val tlsMs = measureTlsIfNeeded(socket, host, server)
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

    private fun measureTlsIfNeeded(socket: Socket, host: String, server: Server): Int {
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
                it.soTimeout = SOCKET_READ_TIMEOUT_MS
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

    private companion object {
        const val FAST_PROBE_PARALLELISM = 12
        const val FAST_PROBE_TOTAL_TIMEOUT_MS = 2_200L
        const val TCP_CONNECT_TIMEOUT_MS = 1_500
        const val SOCKET_READ_TIMEOUT_MS = 1_500
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
