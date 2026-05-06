package com.lisvpn.android.core.domain.model

import kotlinx.datetime.Instant

/**
 * Canonical server representation. Decoupled from any single VPN protocol — instead carries a
 * normalised [Outbound] payload that the sing-box config builder consumes.
 *
 * IDs are deterministic: `<profileId>:<sha1(uri)>` so the same URI imported twice does not
 * duplicate the row.
 */
data class Server(
    val id: String,
    val profileId: String,
    val displayName: String,
    val countryCode: String?,
    val outbound: Outbound,
    val rawUri: String,
    val tags: Set<Tag> = emptySet(),
    val createdAt: Instant,
) {
    enum class Tag {
        Primary,
        MobileBypass,
        FastEdge,
        Backup,
    }
}

/**
 * Protocol-aware payload. Each subtype carries the minimum data sing-box needs to dial the server.
 * Validation is performed on construction by parsers in `:vpn:config`.
 */
sealed interface Outbound {
    val host: String
    val port: Int
    val protocol: Protocol

    data class Vless(
        override val host: String,
        override val port: Int,
        val uuid: String,
        val flow: String?,
        val encryption: String,
        val transport: Transport,
        val security: Security,
    ) : Outbound { override val protocol = Protocol.Vless }

    data class Vmess(
        override val host: String,
        override val port: Int,
        val uuid: String,
        val alterId: Int,
        val cipher: String,
        val transport: Transport,
        val security: Security,
    ) : Outbound { override val protocol = Protocol.Vmess }

    data class Trojan(
        override val host: String,
        override val port: Int,
        val password: String,
        val transport: Transport,
        val security: Security,
    ) : Outbound { override val protocol = Protocol.Trojan }

    data class Shadowsocks(
        override val host: String,
        override val port: Int,
        val password: String,
        val method: String,
    ) : Outbound { override val protocol = Protocol.Shadowsocks }

    enum class Protocol { Vless, Vmess, Trojan, Shadowsocks }
}

/** Transport layer: TCP/WS/gRPC/HTTPUpgrade/XHTTP. Mirrors sing-box transport DSL. */
sealed interface Transport {
    data object Tcp : Transport
    data class WebSocket(val path: String?, val host: String?, val earlyDataHeader: String? = "Sec-WebSocket-Protocol") : Transport
    data class Grpc(val serviceName: String) : Transport
    data class HttpUpgrade(val path: String?, val host: String?) : Transport
    data class XHttp(val path: String?, val host: String?, val mode: String? = null) : Transport
}

/** TLS / Reality / no-security descriptors. */
sealed interface Security {
    data object None : Security
    data class Tls(
        val sni: String?,
        val alpn: List<String> = emptyList(),
        val fingerprint: String? = null,
        val allowInsecure: Boolean = false,
    ) : Security
    data class Reality(
        val sni: String,
        val publicKey: String,
        val shortId: String?,
        val fingerprint: String?,
        val spiderX: String? = null,
    ) : Security
}
