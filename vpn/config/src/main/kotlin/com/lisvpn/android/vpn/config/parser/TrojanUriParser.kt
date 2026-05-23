package com.lisvpn.android.vpn.config.parser

import android.net.Uri
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses `trojan://<password>@host:port?...#<remarks>` subscription entries.
 *
 * Trojan URIs usually omit `security=tls` because TLS is part of the Trojan protocol profile, so
 * TLS is the default here. We support the common 3x-ui/Hiddify parameters for TCP, WS, gRPC and
 * HTTPUpgrade transports and keep unsupported transports explicit instead of silently importing a
 * config that sing-box cannot dial.
 */
@Singleton
class TrojanUriParser @Inject constructor() : UriParser {

    override fun tryParse(uri: String): ParseResult? {
        if (!uri.startsWith("trojan://", ignoreCase = true)) return null
        return runCatching { parseInternal(uri) }
            .getOrElse { ParseResult.Failed(uri, "trojan parse error: ${it.message}") }
    }

    private fun parseInternal(rawUri: String): ParseResult {
        val parsed = Uri.parse(rawUri)
        val password = parsed.userInfo
            ?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Failed(rawUri, "missing user-info (password)")
        val host = parsed.host?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Failed(rawUri, "missing host")
        val port = parsed.port.takeIf { it > 0 }
            ?: return ParseResult.Failed(rawUri, "missing port")

        val params = parsed.queryParameterNames.associateWith { parsed.getQueryParameter(it).orEmpty() }
        val transportType = normalizeTransportType(params["type"] ?: params["network"])
            ?: return ParseResult.Failed(rawUri, "unsupported Trojan transport: ${params["type"] ?: params["network"]}")
        val transport = parseTransport(transportType, params, host)
        val security = parseSecurity(rawUri, params, host)
            ?: return ParseResult.Failed(rawUri, "unsupported Trojan security: ${params["security"]}")

        val outbound = Outbound.Trojan(
            host = host,
            port = port,
            password = password,
            transport = transport,
            security = security,
        )
        val display = parsed.fragment?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return ParseResult.Ok(outbound = outbound, displayName = display, rawUri = rawUri)
    }

    private fun parseSecurity(rawUri: String, params: Map<String, String>, host: String): Security? {
        val securityType = params["security"]?.trim()?.lowercase().orEmpty().ifBlank { "tls" }
        return when (securityType) {
            "tls" -> Security.Tls(
                sni = firstNonBlank(params["sni"], params["peer"], params["host"]) ?: host,
                alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() },
                fingerprint = normalizeFingerprint(params["fp"] ?: params["fingerprint"]),
                allowInsecure = params.isTruthy("allowInsecure") || params.isTruthy("insecure") || params.isTruthy("skip-cert-verify"),
            )
            "none" -> Security.None
            "reality" -> {
                val publicKey = firstNonBlank(params["pbk"], params["publicKey"], params["public_key"])
                    ?: return null
                Security.Reality(
                    sni = firstNonBlank(params["sni"], params["peer"], params["host"]) ?: host,
                    publicKey = publicKey,
                    shortId = firstNonBlank(params["sid"], params["shortId"], params["short_id"]),
                    fingerprint = normalizeFingerprint(params["fp"] ?: params["fingerprint"]),
                    spiderX = params["spx"].takeIf { !it.isNullOrBlank() },
                )
            }
            else -> null
        }
    }

    private fun normalizeTransportType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            null, "", "tcp", "raw", "none" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade", "http-upgrade" -> "httpupgrade"
            "http", "xhttp" -> "http"
            else -> null
        }

    private fun parseTransport(type: String, params: Map<String, String>, host: String): Transport =
        when (type) {
            "tcp" -> Transport.Tcp
            "ws" -> Transport.WebSocket(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = firstHost(params["host"]) ?: host,
            )
            "grpc" -> Transport.Grpc(
                serviceName = firstNonBlank(params["serviceName"], params["service_name"], params["service"]).orEmpty(),
            )
            "httpupgrade" -> Transport.HttpUpgrade(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = firstHost(params["host"]) ?: host,
            )
            "http" -> Transport.XHttp(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = firstHost(params["host"]) ?: host,
                mode = params["mode"].takeIf { !it.isNullOrBlank() },
            )
            else -> Transport.Tcp
        }

    private fun firstHost(value: String?): String? =
        value?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotBlank() }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun Map<String, String>.isTruthy(key: String): Boolean =
        this[key]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

    private fun normalizeFingerprint(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk" -> "chrome"
            "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized" -> normalized
            "none", "off", "false" -> null
            else -> "chrome"
        }
    }
}
