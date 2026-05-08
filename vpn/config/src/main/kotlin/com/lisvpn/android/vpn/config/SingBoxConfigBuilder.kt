package com.lisvpn.android.vpn.config

import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import com.lisvpn.android.core.domain.model.normalizeVlessFlowForSingBox
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber

/**
 * Translates a domain-level [Server] list into a sing-box JSON config string.
 *
 * Layout produced (simplified):
 * ```
 * {
 *   "log": { "level": "warn" },
 *   "dns": { servers, rules },
 *   "inbounds": [ tun ],
 *   "outbounds": [
 *     { type: direct },{ type: block },{ type: dns },
 *     ...per-server outbounds...,
 *     { type: urltest, outbounds: [...] }     // only when smartSelection
 *   ],
 *   "route": { rules, final }
 * }
 * ```
 *
 * Per-app split tunneling is **not** encoded here — it is enforced at the
 * Android VpnService.Builder layer (see LisPlatformInterface).
 */
@Singleton
class SingBoxConfigBuilder @Inject constructor() {

    private val json = Json { prettyPrint = false; encodeDefaults = false }

    fun build(
        servers: List<Server>,
        smartSelection: Boolean,
        @Suppress("UNUSED_PARAMETER") appRules: AppRules,
    ): String {
        require(servers.isNotEmpty()) { "Cannot build sing-box config without servers" }

        val outboundTags = servers.indices.map { index -> "srv-$index" }
        check(outboundTags.distinct().size == outboundTags.size) { "Generated duplicate outbound tags" }
        val finalTag = when {
            smartSelection && servers.size > 1 -> AUTO_TAG
            else -> outboundTags.first()
        }
        check(!smartSelection || servers.size <= 1 || outboundTags.isNotEmpty()) { "Cannot build urltest without server outbounds" }
        Timber.i(
            "Building sing-box config: servers=%d smart=%s final=%s outbounds=%s",
            servers.size,
            smartSelection,
            finalTag,
            servers.joinToString { it.diagnosticLabel() },
        )
        Timber.i(
            "Building sing-box selector: smart=%s includeUrltest=%s final=%s url=%s selectorOutbounds=%s",
            smartSelection,
            smartSelection && servers.size > 1,
            finalTag,
            URLTEST_URL,
            outboundTags.joinToString(),
        )

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "warn")
                put("timestamp", true)
            })
            put("dns", buildDns(finalTag, servers, smartSelection && servers.size > 1))
            putJsonArray("inbounds") { add(buildTunInbound()) }
            putJsonArray("outbounds") {
                add(directOutbound())
                add(blockOutbound())
                add(dnsOutbound())
                servers.forEachIndexed { i, srv -> add(buildServerOutbound(srv, outboundTags[i])) }
                if (smartSelection && servers.size > 1) {
                    add(buildUrltestOutbound(outboundTags))
                }
            }
            put("route", buildRoute(finalTag))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    // ---- Inbound -----------------------------------------------------------

    private fun buildTunInbound(): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("inet4_address", "172.19.0.1/30")
        put("mtu", 1280)
        put("auto_route", true)
        put("strict_route", false)
        put("stack", "system")
        put("sniff", true)
        put("sniff_override_destination", false)
        put("endpoint_independent_nat", true)
    }

    // ---- Outbounds ---------------------------------------------------------

    private fun directOutbound() = buildJsonObject {
        put("type", "direct"); put("tag", DIRECT_TAG)
    }
    private fun blockOutbound() = buildJsonObject {
        put("type", "block"); put("tag", BLOCK_TAG)
    }
    private fun dnsOutbound() = buildJsonObject {
        put("type", "dns"); put("tag", DNS_TAG)
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
        normalizeVlessFlowForSingBox(out.flow)?.let { put("flow", it) }
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

    private fun buildUrltestOutbound(outboundTags: List<String>): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", AUTO_TAG)
        putJsonArray("outbounds") { outboundTags.forEach { add(it) } }
        put("url", URLTEST_URL)
        put("interval", "10m")
        put("tolerance", 50)
    }

    private fun encodeTls(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        security: Security,
        defaultSni: String,
    ) {
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

    private fun encodeTransport(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        transport: Transport,
        defaultHost: String,
    ) {
        when (transport) {
            Transport.Tcp -> Unit
            is Transport.WebSocket -> builder.put("transport", buildJsonObject {
                put("type", "ws")
                transport.path?.let { put("path", it) }
                put("headers", buildJsonObject {
                    put("Host", transport.host ?: defaultHost)
                })
                transport.earlyDataHeader?.let {
                    put("early_data_header_name", it)
                }
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

    // ---- Route & DNS -------------------------------------------------------

    private fun buildRoute(finalTag: String): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        put("override_android_vpn", true)
        put("final", finalTag)
        putJsonArray("rules") {
            // DNS hijack
            addJsonObject {
                put("protocol", "dns")
                put("outbound", DNS_TAG)
            }
            // Block QUIC for misbehaving CDNs (optional but helps in RU)
            addJsonObject {
                put("protocol", "quic")
                put("outbound", BLOCK_TAG)
            }
            addJsonObject {
                put("port", 853)
                put("outbound", BLOCK_TAG)
            }
            // LAN goes direct
            addJsonObject {
                putJsonArray("ip_cidr") {
                    add("10.0.0.0/8")
                    add("172.16.0.0/12")
                    add("192.168.0.0/16")
                    add("169.254.0.0/16")
                }
                put("outbound", DIRECT_TAG)
            }
        }
    }

    private fun buildDns(finalTag: String, servers: List<Server>, includeUrltestDomain: Boolean): JsonObject = buildJsonObject {
        val localDomains = (
            servers.mapNotNull { it.dnsRuleDomain() } +
                listOfNotNull(URLTEST_DOMAIN.takeIf { includeUrltestDomain })
            ).distinct()
        Timber.i(
            "Building sing-box DNS: final=%s localDomains=%s remote=%s address=%s local=%s",
            finalTag,
            localDomains.joinToString(),
            REMOTE_DNS_TAG,
            REMOTE_DNS_ADDRESS,
            LOCAL_DNS_TAG,
        )
        putJsonArray("servers") {
            addJsonObject {
                put("tag", REMOTE_DNS_TAG)
                put("address", REMOTE_DNS_ADDRESS)
                put("detour", DIRECT_TAG)
                put("strategy", "ipv4_only")
            }
            addJsonObject {
                put("tag", LOCAL_DNS_TAG)
                put("address", "local")
                put("detour", DIRECT_TAG)
                put("strategy", "ipv4_only")
            }
            addJsonObject {
                put("tag", BLOCK_DNS_TAG)
                put("address", "rcode://success")
            }
        }
        if (localDomains.isNotEmpty()) {
            putJsonArray("rules") {
                addJsonObject {
                    putJsonArray("domain") { localDomains.forEach { add(it) } }
                    put("server", LOCAL_DNS_TAG)
                }
            }
        }
        put("final", REMOTE_DNS_TAG)
        put("strategy", "ipv4_only")
        put("disable_cache", false)
    }

    private fun Server.dnsRuleDomain(): String? {
        val host = outbound.hostName().trim().removeSuffix(".")
        if (host.contains(':')) return null
        return host.takeIf { value -> value.any { it.isLetter() } }
    }

    private fun Outbound.hostName(): String = when (this) {
        is Outbound.Vless -> host
        is Outbound.Vmess -> host
        is Outbound.Trojan -> host
        is Outbound.Shadowsocks -> host
    }

    private fun Server.diagnosticLabel(): String =
        "$displayName/${outbound.protocol}/${outbound.host}:${outbound.port}"

    private companion object {
        const val DIRECT_TAG = "direct"
        const val BLOCK_TAG = "block"
        const val DNS_TAG = "dns-out"
        const val AUTO_TAG = "auto"
        const val REMOTE_DNS_TAG = "remote"
        const val REMOTE_DNS_ADDRESS = "https://1.1.1.1/dns-query"
        const val LOCAL_DNS_TAG = "local"
        const val BLOCK_DNS_TAG = "block"
        const val URLTEST_DOMAIN = "speed.cloudflare.com"
        const val URLTEST_URL = "https://speed.cloudflare.com/__down?bytes=1048576"
    }
}
