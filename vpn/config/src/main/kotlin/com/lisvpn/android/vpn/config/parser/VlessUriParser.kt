package com.lisvpn.android.vpn.config.parser

import android.net.Uri
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.core.domain.model.normalizeVlessFlowForSingBox
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses `vless://<uuid>@host:port?...&type=...&security=...#<remarks>` URIs.
 *
 * Handles the dialect produced by 3x-ui (and therefore the LisVPN backend), including
 * VLESS Reality, WS+TLS and TCP+TLS transports. Unknown query params are tolerated.
 */
@Singleton
class VlessUriParser @Inject constructor() : UriParser {

    override fun tryParse(uri: String): ParseResult? {
        if (!uri.startsWith("vless://", ignoreCase = true)) return null
        return runCatching { parseInternal(uri) }
            .getOrElse { ParseResult.Failed(uri, "vless parse error: ${it.message}") }
    }

    private fun parseInternal(rawUri: String): ParseResult {
        val parsed = Uri.parse(rawUri)
        val userInfo = parsed.userInfo?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Failed(rawUri, "missing user-info (uuid)")
        val host = parsed.host?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Failed(rawUri, "missing host")
        val port = parsed.port.takeIf { it > 0 }
            ?: return ParseResult.Failed(rawUri, "missing port")

        val params = parsed.queryParameterNames.associateWith { parsed.getQueryParameter(it).orEmpty() }
        val securityType = params["security"]?.trim()?.lowercase().orEmpty()
        val transportType = normalizeTransportType(params["type"])
            ?: return ParseResult.Failed(rawUri, "unsupported VLESS transport: ${params["type"]}")

        val transport = parseTransport(transportType, params, host)
        val security: Security = when (securityType) {
            "reality" -> {
                val publicKey = params["pbk"].orEmpty()
                if (publicKey.isBlank()) return ParseResult.Failed(rawUri, "missing reality public key")
                Security.Reality(
                    sni = params["sni"].takeIf { !it.isNullOrBlank() }
                        ?: params["host"].takeIf { !it.isNullOrBlank() }
                        ?: host,
                    publicKey = publicKey,
                    shortId = params["sid"].takeIf { !it.isNullOrBlank() },
                    fingerprint = normalizeFingerprint(params["fp"]),
                    spiderX = params["spx"].takeIf { !it.isNullOrBlank() },
                )
            }
            "tls" -> Security.Tls(
                sni = params["sni"].takeIf { !it.isNullOrBlank() }
                    ?: params["host"].takeIf { !it.isNullOrBlank() }
                    ?: host,
                alpn = params["alpn"].orEmpty().split(',').filter { it.isNotBlank() },
                fingerprint = normalizeFingerprint(params["fp"]),
                allowInsecure = params["allowInsecure"] == "1" || params["insecure"] == "1",
            )
            else -> Security.None
        }

        val outbound = Outbound.Vless(
            host = host,
            port = port,
            uuid = userInfo,
            flow = normalizeVlessFlowForSingBox(params["flow"]),
            encryption = params["encryption"].orEmpty().ifBlank { "none" },
            transport = transport,
            security = security,
        )

        val display = parsed.fragment?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return ParseResult.Ok(outbound = outbound, displayName = display, rawUri = rawUri)
    }

    private fun normalizeTransportType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            null, "", "tcp", "raw" -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "http" -> "http"
            else -> null
        }

    private fun parseTransport(type: String, params: Map<String, String>, host: String): Transport =
        when (type) {
            "tcp" -> Transport.Tcp
            "ws" -> Transport.WebSocket(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
            )
            "grpc" -> Transport.Grpc(serviceName = params["serviceName"].orEmpty())
            "httpupgrade" -> Transport.HttpUpgrade(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
            )
            "http" -> Transport.XHttp(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
                mode = params["mode"].takeIf { !it.isNullOrBlank() },
            )
            else -> Transport.Tcp
        }

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
