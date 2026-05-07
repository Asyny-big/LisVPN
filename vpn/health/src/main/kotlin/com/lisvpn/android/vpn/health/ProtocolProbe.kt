package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Singleton
class ProtocolProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val unsafeSslSocketFactory: SSLSocketFactory by lazy { buildUnsafeSslSocketFactory() }

    suspend fun probe(server: Server): ProtocolProbeResult = withContext(ioDispatcher) {
        val networkType = currentNetworkType()
        runCatching {
            mutex.withLock { runProbe(server, networkType) }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            Timber.w(
                "Protocol probe failed: server=%s endpoint=%s:%d reason=%s",
                server.displayName,
                server.outbound.host,
                server.outbound.port,
                err.protocolProbeReason(),
            )
            ProtocolProbeResult(
                snapshot = failedSnapshot(server, networkType),
                downloadedBytes = 0L,
                downloadMs = null,
                bytesPerSecond = null,
            )
        }
    }

    private fun runProbe(server: Server, networkType: HealthSnapshot.NetworkType): ProtocolProbeResult =
        runLightweightProbe(server, networkType)

    private fun runLightweightProbe(server: Server, networkType: HealthSnapshot.NetworkType): ProtocolProbeResult {
        val startedAt = SystemClock.elapsedRealtime()
        val probeId = "${server.id.hashCode().toUInt().toString(16)}-$startedAt"
        val outbound = server.outbound
        val security = outbound.security()
        val transport = outbound.transport()
        Timber.i(
            "Protocol probe started: id=%s mode=lightweight server=%s endpoint=%s:%d protocol=%s transport=%s security=%s pid=%d",
            probeId,
            server.displayName,
            outbound.host,
            outbound.port,
            outbound.protocol,
            transport.diagnosticName(),
            security.diagnosticName(),
            Process.myPid(),
        )
        Timber.i("Protocol probe lifecycle: before box create skipped id=%s mode=lightweight", probeId)
        Timber.i("Protocol probe lifecycle: after box create skipped id=%s mode=lightweight", probeId)
        Timber.i("Protocol probe lifecycle: before box start skipped id=%s mode=lightweight", probeId)
        Timber.i("Protocol probe lifecycle: after box start skipped id=%s mode=lightweight", probeId)

        val endpoint = resolveProbeEndpoint(server)
        if (endpoint == null) {
            Timber.w("Protocol probe DNS failed: id=%s server=%s host=%s", probeId, server.displayName, outbound.host)
            return buildLightweightResult(
                server = server,
                networkType = networkType,
                tcp = ProbeStep(false, null, 0L, "DNS resolution failed"),
                tls = null,
                transportStep = null,
                metadata = null,
                startedAt = startedAt,
                probeId = probeId,
            )
        }

        Timber.i(
            "Protocol probe TCP before connect: id=%s server=%s dial=%s:%d originalHost=%s",
            probeId,
            server.displayName,
            endpoint.address.hostAddress,
            endpoint.port,
            endpoint.originalHost,
        )
        val tcp = measureTcpPreflight(endpoint)
        Timber.i(
            "Protocol probe TCP after connect: id=%s server=%s success=%s elapsedMs=%s reason=%s",
            probeId,
            server.displayName,
            tcp.success,
            tcp.elapsedMs,
            tcp.reason,
        )
        if (!tcp.success) {
            return buildLightweightResult(
                server = server,
                networkType = networkType,
                tcp = tcp,
                tls = null,
                transportStep = null,
                metadata = null,
                startedAt = startedAt,
                probeId = probeId,
            )
        }

        val tls = when (security) {
            is Security.Tls -> {
                val serverName = tlsServerName(endpoint, outbound, security)
                Timber.i(
                    "Protocol probe TLS before handshake: id=%s server=%s sni=%s allowInsecure=%s",
                    probeId,
                    server.displayName,
                    serverName,
                    security.allowInsecure,
                )
                measureTlsPreflight(endpoint, serverName, security.allowInsecure).also { step ->
                    Timber.i(
                        "Protocol probe TLS after handshake: id=%s server=%s success=%s elapsedMs=%s reason=%s",
                        probeId,
                        server.displayName,
                        step.success,
                        step.elapsedMs,
                        step.reason,
                    )
                }
            }
            else -> null
        }

        val metadata = when (security) {
            is Security.Reality -> validateRealityMetadata(security, tcp.elapsedMs)
            else -> null
        }
        if (metadata != null) {
            Timber.i(
                "Protocol probe Reality metadata: id=%s server=%s success=%s elapsedMs=%s reason=%s",
                probeId,
                server.displayName,
                metadata.success,
                metadata.elapsedMs,
                metadata.reason,
            )
        }

        val transportStep = if (transport.requiresHttpPreflight() && security !is Security.Reality) {
            Timber.i(
                "Protocol probe HTTP before test: id=%s server=%s transport=%s",
                probeId,
                server.displayName,
                transport.diagnosticName(),
            )
            measureHttpTransportPreflight(endpoint, outbound, security, transport).also { step ->
                Timber.i(
                    "Protocol probe HTTP after test: id=%s server=%s success=%s elapsedMs=%s bytes=%d reason=%s",
                    probeId,
                    server.displayName,
                    step.success,
                    step.elapsedMs,
                    step.bytesRead,
                    step.reason,
                )
            }
        } else {
            Timber.i(
                "Protocol probe HTTP skipped: id=%s server=%s transport=%s security=%s",
                probeId,
                server.displayName,
                transport.diagnosticName(),
                security.diagnosticName(),
            )
            null
        }

        return buildLightweightResult(
            server = server,
            networkType = networkType,
            tcp = tcp,
            tls = tls,
            transportStep = transportStep,
            metadata = metadata,
            startedAt = startedAt,
            probeId = probeId,
        )
    }

    private fun buildLightweightResult(
        server: Server,
        networkType: HealthSnapshot.NetworkType,
        tcp: ProbeStep,
        tls: ProbeStep?,
        transportStep: ProbeStep?,
        metadata: ProbeStep?,
        startedAt: Long,
        probeId: String,
    ): ProtocolProbeResult {
        val outbound = server.outbound
        val security = outbound.security()
        val transport = outbound.transport()
        val validationSteps = listOfNotNull(tcp, tls, metadata, transportStep)
        val success = when {
            !tcp.success -> false
            transport.requiresHttpPreflight() && security !is Security.Reality -> transportStep?.success == true
            security is Security.Tls -> tls?.success == true
            security is Security.Reality -> metadata?.success == true
            outbound is Outbound.Shadowsocks -> true
            security == Security.None -> true
            else -> false
        }
        val elapsedMs = elapsedSince(startedAt)
        val bestProtocolMs = listOfNotNull(transportStep?.elapsedMs, tls?.elapsedMs, metadata?.elapsedMs, tcp.elapsedMs).minOrNull()
        val downloadedBytes = transportStep?.bytesRead ?: 0L
        val downloadMs = transportStep?.elapsedMs
        val snapshot = HealthSnapshot(
            serverId = server.id,
            timestamp = Clock.System.now(),
            tcpHandshakeMs = tcp.elapsedMs,
            tlsHandshakeMs = tls?.elapsedMs?.takeIf { tls.success },
            httpRttMs = bestProtocolMs,
            success = success,
            networkType = networkType,
        )
        Timber.i(
            "Protocol probe completed: id=%s mode=lightweight server=%s success=%s tcpMs=%s tlsMs=%s httpMs=%s checks=%d/%d bytes=%d elapsedMs=%d",
            probeId,
            server.displayName,
            success,
            tcp.elapsedMs,
            tls?.elapsedMs,
            transportStep?.elapsedMs,
            validationSteps.count { it.success },
            validationSteps.size,
            downloadedBytes,
            elapsedMs,
        )
        Timber.i("Protocol probe lifecycle: before shutdown skipped id=%s mode=lightweight", probeId)
        Timber.i("Protocol probe lifecycle: after shutdown skipped id=%s mode=lightweight", probeId)
        return ProtocolProbeResult(
            snapshot = snapshot,
            downloadedBytes = downloadedBytes,
            downloadMs = downloadMs,
            bytesPerSecond = null,
            startupMs = elapsedMs,
            latencySamplesMs = listOfNotNull(tcp.elapsedMs, tls?.elapsedMs, transportStep?.elapsedMs),
            internetCheckCount = validationSteps.size,
            internetSuccessCount = validationSteps.count { it.success },
            blockedCheckCount = 0,
            blockedSuccessCount = 0,
        )
    }

    private fun resolveProbeEndpoint(server: Server): ProbeEndpoint? {
        val host = server.outbound.host.trim().removeSuffix(".")
        if (host.isBlank() || host.contains(':')) return null
        val address = runCatching {
            InetAddress.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
        }.getOrNull() ?: return null
        return ProbeEndpoint(
            originalHost = host,
            address = address,
            port = server.outbound.port,
        )
    }

    private fun measureTcpPreflight(endpoint: ProbeEndpoint): ProbeStep {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            connectPlainSocket(endpoint).use { }
            ProbeStep(
                success = true,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = 0L,
                reason = null,
            )
        }.getOrElse { err ->
            ProbeStep(
                success = false,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = 0L,
                reason = err.protocolProbeReason(),
            )
        }
    }

    private fun measureTlsPreflight(endpoint: ProbeEndpoint, serverName: String?, allowInsecure: Boolean): ProbeStep {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            connectTlsSocket(endpoint, serverName, allowInsecure).use { }
            ProbeStep(
                success = true,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = 0L,
                reason = null,
            )
        }.getOrElse { err ->
            ProbeStep(
                success = false,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = 0L,
                reason = err.protocolProbeReason(),
            )
        }
    }

    private fun validateRealityMetadata(security: Security.Reality, tcpMs: Int?): ProbeStep {
        val success = security.sni.isNotBlank() && security.publicKey.isNotBlank()
        return ProbeStep(
            success = success,
            elapsedMs = tcpMs,
            bytesRead = 0L,
            reason = if (success) null else "Reality metadata is incomplete",
        )
    }

    private fun measureHttpTransportPreflight(
        endpoint: ProbeEndpoint,
        outbound: Outbound,
        security: Security,
        transport: Transport,
    ): ProbeStep {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val serverName = when (security) {
                is Security.Tls -> tlsServerName(endpoint, outbound, security)
                else -> null
            }
            val hostHeader = transport.hostHeader() ?: serverName ?: endpoint.originalHost
            val request = transport.httpRequest(transport.httpPath(), hostHeader)
            val response = openProtocolSocket(endpoint, security, serverName).use { socket ->
                socket.soTimeout = HTTP_READ_TIMEOUT_MS
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()
                val buffer = ByteArray(HTTP_RESPONSE_LIMIT_BYTES)
                val read = BufferedInputStream(socket.getInputStream()).read(buffer)
                if (read <= 0) error("Empty HTTP response")
                String(buffer, 0, read, Charsets.ISO_8859_1)
            }
            val status = response.httpStatusCode()
            val success = status in 100..499
            ProbeStep(
                success = success,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = response.length.toLong(),
                reason = status?.let { "HTTP $it" } ?: "No HTTP status",
            )
        }.getOrElse { err ->
            ProbeStep(
                success = false,
                elapsedMs = elapsedSince(startedAt),
                bytesRead = 0L,
                reason = err.protocolProbeReason(),
            )
        }
    }

    private fun openProtocolSocket(endpoint: ProbeEndpoint, security: Security, serverName: String?): Socket =
        when (security) {
            Security.None -> connectPlainSocket(endpoint)
            is Security.Tls -> connectTlsSocket(endpoint, serverName, security.allowInsecure)
            is Security.Reality -> error("Reality transport requires native client")
        }

    private fun connectPlainSocket(endpoint: ProbeEndpoint): Socket {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.soTimeout = HTTP_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), TCP_CONNECT_TIMEOUT_MS)
            socket
        } catch (err: Throwable) {
            runCatching { socket.close() }
            throw err
        }
    }

    private fun connectTlsSocket(endpoint: ProbeEndpoint, serverName: String?, allowInsecure: Boolean): SSLSocket {
        var plain: Socket? = connectPlainSocket(endpoint)
        var ssl: SSLSocket? = null
        return try {
            val peerHost = serverName ?: endpoint.originalHost
            val factory = if (allowInsecure) unsafeSslSocketFactory else SSLSocketFactory.getDefault() as SSLSocketFactory
            ssl = factory.createSocket(plain, peerHost, endpoint.port, true) as SSLSocket
            plain = null
            ssl.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
            ssl.useClientMode = true
            val parameters = ssl.sslParameters
            if (!allowInsecure) parameters.endpointIdentificationAlgorithm = "HTTPS"
            if (serverName != null && serverName.canBeSniHost()) {
                parameters.serverNames = listOf(SNIHostName(serverName))
            }
            ssl.sslParameters = parameters
            ssl.startHandshake()
            ssl
        } catch (err: Throwable) {
            runCatching { ssl?.close() }
            runCatching { plain?.close() }
            throw err
        }
    }

    private fun buildUnsafeSslSocketFactory(): SSLSocketFactory {
        val trustManagers = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustManagers, SecureRandom())
        return context.socketFactory
    }

    private fun tlsServerName(endpoint: ProbeEndpoint, outbound: Outbound, security: Security.Tls): String? =
        security.sni?.takeIf { it.isNotBlank() }
            ?: outbound.transport().hostHeader()?.takeIf { it.canBeSniHost() }
            ?: endpoint.originalHost.takeIf { it.canBeSniHost() }

    private fun Transport.httpRequest(path: String, host: String): String =
        when (this) {
            is Transport.WebSocket,
            is Transport.HttpUpgrade -> buildString {
                append("GET ")
                append(path)
                append(" HTTP/1.1\r\n")
                append("Host: ")
                append(host)
                append("\r\n")
                append("User-Agent: ")
                append(USER_AGENT)
                append("\r\n")
                append("Connection: Upgrade\r\n")
                append("Upgrade: websocket\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("\r\n")
            }
            is Transport.XHttp -> buildString {
                append("HEAD ")
                append(path)
                append(" HTTP/1.1\r\n")
                append("Host: ")
                append(host)
                append("\r\n")
                append("User-Agent: ")
                append(USER_AGENT)
                append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            Transport.Tcp,
            is Transport.Grpc -> error("Transport does not use HTTP preflight")
        }

    private fun Transport.httpPath(): String =
        when (this) {
            is Transport.WebSocket -> path
            is Transport.HttpUpgrade -> path
            is Transport.XHttp -> path
            Transport.Tcp,
            is Transport.Grpc -> null
        }.normalizeHttpPath()

    private fun String?.normalizeHttpPath(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: "/"
        return if (value.startsWith("/")) value else "/$value"
    }

    private fun String.httpStatusCode(): Int? {
        val firstLine = lineSequence().firstOrNull().orEmpty()
        val parts = firstLine.split(' ')
        return parts.getOrNull(1)?.toIntOrNull()
    }

    private fun Transport.requiresHttpPreflight(): Boolean =
        this is Transport.WebSocket || this is Transport.HttpUpgrade || this is Transport.XHttp

    private fun Outbound.security(): Security = when (this) {
        is Outbound.Vless -> security
        is Outbound.Vmess -> security
        is Outbound.Trojan -> security
        is Outbound.Shadowsocks -> Security.None
    }

    private fun Outbound.transport(): Transport = when (this) {
        is Outbound.Vless -> transport
        is Outbound.Vmess -> transport
        is Outbound.Trojan -> transport
        is Outbound.Shadowsocks -> Transport.Tcp
    }

    private fun Transport.hostHeader(): String? = when (this) {
        Transport.Tcp -> null
        is Transport.WebSocket -> host
        is Transport.Grpc -> null
        is Transport.HttpUpgrade -> host
        is Transport.XHttp -> host
    }?.takeIf { it.isNotBlank() }

    private fun Transport.diagnosticName(): String = when (this) {
        Transport.Tcp -> "tcp"
        is Transport.WebSocket -> "ws"
        is Transport.Grpc -> "grpc"
        is Transport.HttpUpgrade -> "httpupgrade"
        is Transport.XHttp -> "xhttp"
    }

    private fun Security.diagnosticName(): String = when (this) {
        Security.None -> "none"
        is Security.Tls -> "tls"
        is Security.Reality -> "reality"
    }

    private fun String.canBeSniHost(): Boolean =
        isNotBlank() && any { it.isLetter() } && !contains(':')

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private fun currentNetworkType(): HealthSnapshot.NetworkType {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return HealthSnapshot.NetworkType.Unknown
        val caps = manager.getNetworkCapabilities(network) ?: return HealthSnapshot.NetworkType.Unknown
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> HealthSnapshot.NetworkType.VpnInterface
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && roaming -> HealthSnapshot.NetworkType.CellularRoaming
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && metered -> HealthSnapshot.NetworkType.CellularMetered
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> HealthSnapshot.NetworkType.Cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && metered -> HealthSnapshot.NetworkType.WifiMetered
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> HealthSnapshot.NetworkType.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> HealthSnapshot.NetworkType.Ethernet
            metered -> HealthSnapshot.NetworkType.Metered
            else -> HealthSnapshot.NetworkType.Unknown
        }
    }

    private fun failedSnapshot(server: Server, networkType: HealthSnapshot.NetworkType): HealthSnapshot = HealthSnapshot(
        serverId = server.id,
        timestamp = Clock.System.now(),
        tcpHandshakeMs = null,
        tlsHandshakeMs = null,
        httpRttMs = null,
        success = false,
        networkType = networkType,
    )

    private fun Throwable.protocolProbeReason(): String =
        "${this::class.java.simpleName}: ${message.orEmpty()}"

    private data class ProbeEndpoint(
        val originalHost: String,
        val address: Inet4Address,
        val port: Int,
    )

    private data class ProbeStep(
        val success: Boolean,
        val elapsedMs: Int?,
        val bytesRead: Long,
        val reason: String?,
    )

    private companion object {
        const val USER_AGENT = "LisVPN/ProtocolProbe"
        const val TCP_CONNECT_TIMEOUT_MS = 2_500
        const val TLS_HANDSHAKE_TIMEOUT_MS = 3_500
        const val HTTP_READ_TIMEOUT_MS = 3_500
        const val HTTP_RESPONSE_LIMIT_BYTES = 4 * 1024
    }
}

data class ProtocolProbeResult(
    val snapshot: HealthSnapshot,
    val downloadedBytes: Long,
    val downloadMs: Int?,
    val bytesPerSecond: Long?,
    val startupMs: Int? = null,
    val latencySamplesMs: List<Int> = emptyList(),
    val internetCheckCount: Int = 0,
    val internetSuccessCount: Int = 0,
    val blockedCheckCount: Int = 0,
    val blockedSuccessCount: Int = 0,
)
