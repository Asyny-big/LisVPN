package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.vpn.libbox.LibboxEnvironment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.NetworkInterface
import libbox.NetworkInterfaceIterator
import libbox.PlatformInterface
import libbox.TunOptions
import libbox.WIFIState
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

@Singleton
class ProtocolProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = false; encodeDefaults = false }

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

    private suspend fun runProbe(server: Server, networkType: HealthSnapshot.NetworkType): ProtocolProbeResult {
        val port = allocateLoopbackPort()
        val config = buildProbeConfig(server, port)
        var box: libbox.BoxService? = null
        return try {
            LibboxEnvironment.ensureInitialized(context)
            Libbox.setMemoryLimit(true)
            Libbox.checkConfig(config)
            box = Libbox.newService(config, ProbePlatformInterface)
            box.start()
            delay(PROXY_WARMUP_MS)

            val latency = fetchThroughProxy(port, LATENCY_URL, maxBytes = 0)
            val download = fetchThroughProxy(port, DOWNLOAD_URL, maxBytes = DOWNLOAD_LIMIT_BYTES)
            val bytesPerSecond = if (download.elapsedMs > 0) {
                (download.bytesRead * 1_000L) / download.elapsedMs.toLong()
            } else {
                download.bytesRead * 1_000L
            }
            val snapshot = HealthSnapshot(
                serverId = server.id,
                timestamp = Clock.System.now(),
                tcpHandshakeMs = null,
                tlsHandshakeMs = null,
                httpRttMs = latency.elapsedMs,
                success = download.bytesRead >= MIN_SUCCESS_BYTES,
                networkType = networkType,
            )
            Timber.i(
                "Protocol probe success: server=%s latencyMs=%d downloadBytes=%d downloadMs=%d speedKbps=%d",
                server.displayName,
                latency.elapsedMs,
                download.bytesRead,
                download.elapsedMs,
                (bytesPerSecond * 8L / 1_000L).coerceAtLeast(1L),
            )
            ProtocolProbeResult(
                snapshot = snapshot,
                downloadedBytes = download.bytesRead,
                downloadMs = download.elapsedMs,
                bytesPerSecond = bytesPerSecond,
            )
        } finally {
            runCatching { box?.close() }
        }
    }

    private fun fetchThroughProxy(port: Int, url: String, maxBytes: Int): FetchResult {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK_HOST, port))
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
        connection.readTimeout = HTTP_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP status $status")
            var bytesRead = 0L
            if (maxBytes > 0) {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (bytesRead < maxBytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - bytesRead.toInt()))
                        if (read == -1) break
                        bytesRead += read.toLong()
                    }
                }
            }
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)
            FetchResult(bytesRead = bytesRead, elapsedMs = elapsedMs)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildProbeConfig(server: Server, port: Int): String {
        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "warn")
                put("timestamp", true)
            })
            put("dns", buildDns(server))
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "mixed")
                    put("tag", INBOUND_TAG)
                    put("listen", LOOPBACK_HOST)
                    put("listen_port", port)
                    put("sniff", true)
                    put("sniff_override_destination", false)
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "direct")
                    put("tag", DIRECT_TAG)
                }
                add(buildServerOutbound(server, SERVER_TAG))
            }
            put("route", buildJsonObject {
                put("auto_detect_interface", true)
                put("final", SERVER_TAG)
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun buildDns(server: Server): JsonObject = buildJsonObject {
        val serverDomain = server.dnsRuleDomain()
        putJsonArray("servers") {
            addJsonObject {
                put("tag", REMOTE_DNS_TAG)
                put("address", REMOTE_DNS_ADDRESS)
                put("detour", SERVER_TAG)
                put("strategy", "ipv4_only")
            }
            addJsonObject {
                put("tag", LOCAL_DNS_TAG)
                put("address", "local")
                put("detour", DIRECT_TAG)
            }
        }
        serverDomain?.let { domain ->
            putJsonArray("rules") {
                addJsonObject {
                    putJsonArray("domain") { add(domain) }
                    put("server", LOCAL_DNS_TAG)
                }
            }
        }
        put("final", REMOTE_DNS_TAG)
        put("strategy", "ipv4_only")
        put("disable_cache", true)
    }

    private fun buildServerOutbound(server: Server, tag: String): JsonObject = when (val out = server.outbound) {
        is Outbound.Vless -> buildVlessOutbound(out, tag)
        is Outbound.Vmess -> buildVmessOutbound(out, tag)
        is Outbound.Trojan -> buildTrojanOutbound(out, tag)
        is Outbound.Shadowsocks -> buildShadowsocksOutbound(out, tag)
    }

    private fun buildVlessOutbound(out: Outbound.Vless, tag: String): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", tag)
        put("server", out.host)
        put("server_port", out.port)
        put("uuid", out.uuid)
        out.flow?.let { put("flow", it) }
        if (out.encryption.isNotBlank() && out.encryption != "none") put("packet_encoding", out.encryption)
        encodeTls(this, out.security, defaultSni = out.host)
        encodeTransport(this, out.transport, defaultHost = out.host)
        put("domain_strategy", "ipv4_only")
    }

    private fun buildVmessOutbound(out: Outbound.Vmess, tag: String): JsonObject = buildJsonObject {
        put("type", "vmess")
        put("tag", tag)
        put("server", out.host)
        put("server_port", out.port)
        put("uuid", out.uuid)
        put("alter_id", out.alterId)
        put("security", out.cipher.ifBlank { "auto" })
        encodeTls(this, out.security, defaultSni = out.host)
        encodeTransport(this, out.transport, defaultHost = out.host)
        put("domain_strategy", "ipv4_only")
    }

    private fun buildTrojanOutbound(out: Outbound.Trojan, tag: String): JsonObject = buildJsonObject {
        put("type", "trojan")
        put("tag", tag)
        put("server", out.host)
        put("server_port", out.port)
        put("password", out.password)
        encodeTls(this, out.security, defaultSni = out.host)
        encodeTransport(this, out.transport, defaultHost = out.host)
        put("domain_strategy", "ipv4_only")
    }

    private fun buildShadowsocksOutbound(out: Outbound.Shadowsocks, tag: String): JsonObject = buildJsonObject {
        put("type", "shadowsocks")
        put("tag", tag)
        put("server", out.host)
        put("server_port", out.port)
        put("method", out.method)
        put("password", out.password)
        put("domain_strategy", "ipv4_only")
    }

    private fun encodeTls(builder: JsonObjectBuilder, security: Security, defaultSni: String) {
        when (security) {
            Security.None -> Unit
            is Security.Tls -> builder.put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", security.sni ?: defaultSni)
                if (security.alpn.isNotEmpty()) putJsonArray("alpn") { security.alpn.forEach { add(it) } }
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", security.fingerprint ?: "chrome")
                })
                if (security.allowInsecure) put("insecure", true)
            })
            is Security.Reality -> builder.put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", security.sni)
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", security.fingerprint ?: "chrome")
                })
                put("reality", buildJsonObject {
                    put("enabled", true)
                    put("public_key", security.publicKey)
                    put("short_id", security.shortId.orEmpty())
                })
            })
        }
    }

    private fun encodeTransport(builder: JsonObjectBuilder, transport: Transport, defaultHost: String) {
        when (transport) {
            Transport.Tcp -> Unit
            is Transport.WebSocket -> builder.put("transport", buildJsonObject {
                put("type", "ws")
                transport.path?.let { put("path", it) }
                put("headers", buildJsonObject {
                    put("Host", transport.host ?: defaultHost)
                })
                transport.earlyDataHeader?.let { put("early_data_header_name", it) }
            })
            is Transport.Grpc -> builder.put("transport", buildJsonObject {
                put("type", "grpc")
                put("service_name", transport.serviceName)
            })
            is Transport.HttpUpgrade -> builder.put("transport", buildJsonObject {
                put("type", "httpupgrade")
                transport.path?.let { put("path", it) }
                put("host", transport.host ?: defaultHost)
            })
            is Transport.XHttp -> builder.put("transport", buildJsonObject {
                put("type", "http")
                transport.path?.let { put("path", it) }
                put("host", buildJsonArray { add(transport.host ?: defaultHost) })
            })
        }
    }

    private fun allocateLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).use { socket ->
        socket.localPort
    }

    private fun Server.dnsRuleDomain(): String? {
        val host = outbound.host.trim().removeSuffix(".")
        if (host.contains(':')) return null
        return host.takeIf { value -> value.any { it.isLetter() } }
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

    private data class FetchResult(
        val bytesRead: Long,
        val elapsedMs: Int,
    )

    private object ProbePlatformInterface : PlatformInterface {
        override fun openTun(options: TunOptions): Int = error("Protocol probe config must not request TUN")
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun autoDetectInterfaceControl(fd: Int) = Unit
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(
            ipProto: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): Int = 0
        override fun packageNameByUid(uid: Int): String = ""
        override fun uidByPackageName(packageName: String): Int = 0
        override fun usePlatformDefaultInterfaceMonitor(): Boolean = false
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
        override fun usePlatformInterfaceGetter(): Boolean = false
        override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkInterfaceIterator
        override fun underNetworkExtension(): Boolean = false
        override fun clearDNSCache() = Unit
        override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")
        override fun writeLog(message: String) {
            Timber.tag(TAG_LIBBOX_PROBE).d(message)
        }
    }

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = error("No network interfaces in probe")
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val INBOUND_TAG = "probe-in"
        const val SERVER_TAG = "probe"
        const val DIRECT_TAG = "direct"
        const val REMOTE_DNS_TAG = "remote"
        const val REMOTE_DNS_ADDRESS = "tcp://1.1.1.1"
        const val LOCAL_DNS_TAG = "local"
        const val LATENCY_URL = "https://www.gstatic.com/generate_204"
        const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=262144"
        const val USER_AGENT = "LisVPN/ProtocolProbe"
        const val PROXY_WARMUP_MS = 250L
        const val HTTP_CONNECT_TIMEOUT_MS = 6_000
        const val HTTP_READ_TIMEOUT_MS = 10_000
        const val DOWNLOAD_LIMIT_BYTES = 256 * 1024
        const val MIN_SUCCESS_BYTES = 32 * 1024
        const val BUFFER_SIZE = 16 * 1024
        const val TAG_LIBBOX_PROBE = "libbox-probe"
    }
}

data class ProtocolProbeResult(
    val snapshot: HealthSnapshot,
    val downloadedBytes: Long,
    val downloadMs: Int?,
    val bytesPerSecond: Long?,
)
