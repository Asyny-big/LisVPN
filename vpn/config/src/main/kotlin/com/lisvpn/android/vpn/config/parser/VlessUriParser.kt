package com.lisvpn.android.vpn.config.parser

import android.net.Uri
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Transport
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
        val securityType = params["security"]?.lowercase().orEmpty()

        val transport = parseTransport(params, host)
        val security: Security = when (securityType) {
            "reality" -> {
                val publicKey = params["pbk"].orEmpty()
                if (publicKey.isBlank()) return ParseResult.Failed(rawUri, "missing reality public key")
                Security.Reality(
                    sni = params["sni"].orEmpty().ifBlank { host },
                    publicKey = publicKey,
                    shortId = params["sid"].takeIf { !it.isNullOrBlank() },
                    fingerprint = params["fp"].takeIf { !it.isNullOrBlank() },
                    spiderX = params["spx"].takeIf { !it.isNullOrBlank() },
                )
            }
            "tls" -> Security.Tls(
                sni = params["sni"].takeIf { !it.isNullOrBlank() } ?: host,
                alpn = params["alpn"].orEmpty().split(',').filter { it.isNotBlank() },
                fingerprint = params["fp"].takeIf { !it.isNullOrBlank() },
                allowInsecure = params["allowInsecure"] == "1",
            )
            else -> Security.None
        }

        val outbound = Outbound.Vless(
            host = host,
            port = port,
            uuid = userInfo,
            flow = params["flow"].takeIf { !it.isNullOrBlank() },
            encryption = params["encryption"].orEmpty().ifBlank { "none" },
            transport = transport,
            security = security,
        )

        val display = parsed.fragment?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return ParseResult.Ok(outbound = outbound, displayName = display, rawUri = rawUri)
    }

    private fun parseTransport(params: Map<String, String>, host: String): Transport =
        when (params["type"]?.lowercase()) {
            "tcp", null, "" -> Transport.Tcp
            "ws" -> Transport.WebSocket(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
            )
            "grpc" -> Transport.Grpc(serviceName = params["serviceName"].orEmpty())
            "httpupgrade" -> Transport.HttpUpgrade(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
            )
            "xhttp" -> Transport.XHttp(
                path = params["path"].takeIf { !it.isNullOrBlank() },
                host = params["host"].takeIf { !it.isNullOrBlank() } ?: host,
                mode = params["mode"].takeIf { !it.isNullOrBlank() },
            )
            else -> Transport.Tcp // unknown -> safe default
        }
}
