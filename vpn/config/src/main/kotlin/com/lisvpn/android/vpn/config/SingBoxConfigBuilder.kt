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
 *     { type: selector, outbounds: [...], default: first } // only when smartSelection
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
        val includeSelector = smartSelection && servers.size > 1
        val finalTag = if (includeSelector) AUTO_TAG else outboundTags.first()
        check(!includeSelector || outboundTags.isNotEmpty()) { "Cannot build selector without server outbounds" }
        Timber.i(
            "Building sing-box config: servers=%d smart=%s final=%s outbounds=%s",
            servers.size,
            smartSelection,
            finalTag,
            servers.joinToString { it.diagnosticLabel() },
        )
        Timber.i(
            "Building sing-box selector: smart=%s includeSelector=%s final=%s default=%s selectorOutbounds=%s",
            smartSelection,
            includeSelector,
            finalTag,
            outboundTags.first(),
            outboundTags.joinToString(),
        )

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "info")
                put("timestamp", true)
            })
            put("dns", buildDns(finalTag, servers))
            putJsonArray("inbounds") {
                add(buildTunInbound())
                if (smartSelection) add(buildOptimizerMixedInbound())
            }
            putJsonArray("outbounds") {
                add(directOutbound())
                add(blockOutbound())
                add(dnsOutbound())
                servers.forEachIndexed { i, srv -> add(buildServerOutbound(srv, outboundTags[i])) }
                if (includeSelector) {
                    add(buildSelectorOutbound(outboundTags))
                    add(buildOptimizerSelectorOutbound(outboundTags))
                }
            }
            put("route", buildRoute(finalTag, optimizerEnabled = includeSelector))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Builds a *headless* sing-box config used during the AUTO mode pre-VPN speed test.
     *
     * The user's expectation in AUTO mode is that we measure each candidate's download speed
     * directly from their current network — not from inside an already-established VPN tunnel.
     * To do that we run libbox in SOCKS-only mode: no `tun` inbound, only the SOCKS5 mixed
     * inbound on `127.0.0.1:[OPTIMIZER_SOCKS_PORT]`. Because no TUN is opened, the user's apps
     * keep using their normal network unchanged; meanwhile the speed-test loop in
     * [com.lisvpn.android.core.data.repository.AutoOptimizerRepositoryImpl] dials the SOCKS port
     * once per candidate and downloads a 2 MiB chunk through that candidate's VLESS / Reality
     * outbound, which is exactly the "пингую сервер напрямую с моей сети" semantic the user
     * asked for.
     *
     * The selector tag is the same [OPTIMIZER_SELECTOR_TAG] used by the post-connect optimizer
     * so the existing `selectOutbound("auto-optimizer", "srv-N")` plumbing works unchanged.
     */
    fun buildPreflight(
        servers: List<Server>,
        @Suppress("UNUSED_PARAMETER") appRules: AppRules,
    ): String {
        require(servers.isNotEmpty()) { "Cannot build preflight config without servers" }
        val outboundTags = servers.indices.map { index -> "srv-$index" }
        check(outboundTags.distinct().size == outboundTags.size) { "Generated duplicate outbound tags" }
        Timber.i(
            "Building sing-box preflight config: servers=%d default=%s outbounds=%s",
            servers.size,
            outboundTags.first(),
            servers.joinToString { it.diagnosticLabel() },
        )

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "info")
                put("timestamp", true)
            })
            put("dns", buildPreflightDns())
            putJsonArray("inbounds") {
                add(buildOptimizerMixedInbound())
            }
            putJsonArray("outbounds") {
                add(directOutbound())
                add(blockOutbound())
                add(dnsOutbound())
                servers.forEachIndexed { i, srv -> add(buildServerOutbound(srv, outboundTags[i])) }
                add(buildOptimizerSelectorOutbound(outboundTags))
            }
            put("route", buildPreflightRoute())
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

    private fun buildOptimizerMixedInbound(): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", OPTIMIZER_INBOUND_TAG)
        put("listen", "127.0.0.1")
        put("listen_port", OPTIMIZER_SOCKS_PORT)
        put("sniff", true)
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

    private fun buildSelectorOutbound(outboundTags: List<String>): JsonObject = buildJsonObject {
        put("type", "selector")
        put("tag", AUTO_TAG)
        putJsonArray("outbounds") { outboundTags.forEach { add(it) } }
        put("default", outboundTags.first())
        put("interrupt_exist_connections", true)
    }

    private fun buildOptimizerSelectorOutbound(outboundTags: List<String>): JsonObject = buildJsonObject {
        put("type", "selector")
        put("tag", OPTIMIZER_SELECTOR_TAG)
        putJsonArray("outbounds") { outboundTags.forEach { add(it) } }
        put("default", outboundTags.first())
        put("interrupt_exist_connections", true)
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

    private fun buildPreflightRoute(): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        put("override_android_vpn", true)
        // Preflight has no TUN inbound; the only inbound is the SOCKS5 mixed proxy used by the
        // speed-test loop. We therefore route every accepted connection straight to the per-server
        // selector. The DNS / QUIC / LAN guards used for the real tunnel are intentionally
        // omitted — the speed-test traffic is already destined for known speed-test endpoints.
        put("final", OPTIMIZER_SELECTOR_TAG)
        putJsonArray("rules") {
            addJsonObject {
                putJsonArray("inbound") { add(OPTIMIZER_INBOUND_TAG) }
                put("outbound", OPTIMIZER_SELECTOR_TAG)
            }
        }
    }

    private fun buildPreflightDns(): JsonObject = buildJsonObject {
        // The OkHttp / HttpURLConnection client used by the preflight probe resolves DNS itself
        // (Java's SOCKS5 stack does client-side DNS), so libbox does not need a working DNS for
        // the speed-test endpoints. We still wire a cloud DoH server because libbox refuses to
        // start without at least one DNS server.
        putJsonArray("servers") {
            addJsonObject {
                put("tag", REMOTE_DNS_TAG)
                put("address", REMOTE_DNS_ADDRESS)
                put("detour", DIRECT_TAG)
                put("strategy", "ipv4_only")
            }
        }
        put("final", REMOTE_DNS_TAG)
        put("strategy", "ipv4_only")
        put("disable_cache", false)
    }

    private fun buildRoute(finalTag: String, optimizerEnabled: Boolean): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        put("override_android_vpn", true)
        put("final", finalTag)
        putJsonArray("rules") {
            if (optimizerEnabled) {
                addJsonObject {
                    putJsonArray("inbound") { add(OPTIMIZER_INBOUND_TAG) }
                    put("outbound", OPTIMIZER_SELECTOR_TAG)
                }
            }
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

    private fun buildDns(finalTag: String, servers: List<Server>): JsonObject = buildJsonObject {
        val localDomains = servers.mapNotNull { it.dnsRuleDomain() }.distinct()
        Timber.i(
            "Building sing-box DNS: final=%s localDomains=%s remote=%s address=%s local=%s address=%s",
            finalTag,
            localDomains.joinToString(),
            REMOTE_DNS_TAG,
            REMOTE_DNS_ADDRESS,
            LOCAL_DNS_TAG,
            LOCAL_DNS_ADDRESS,
        )
        putJsonArray("servers") {
            addJsonObject {
                put("tag", REMOTE_DNS_TAG)
                put("address", REMOTE_DNS_ADDRESS)
                put("detour", finalTag)
                put("strategy", "ipv4_only")
            }
            // Local DNS is intentionally NOT `address: "local"`. On Android the Go resolver
            // backing `local` consults the OS, and the OS hands out the in-tunnel DNS once the
            // VPN is up - which means resolving the server's own hostname routes the query
            // through the tunnel that hasn't come up yet. Pinning local DNS to a real DoH
            // endpoint with `detour: direct` short-circuits that loop and is what lets manual
            // selection of a hostname-only server (e.g. govchat.ru) actually connect.
            addJsonObject {
                put("tag", LOCAL_DNS_TAG)
                put("address", LOCAL_DNS_ADDRESS)
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
        const val OPTIMIZER_SELECTOR_TAG = "auto-optimizer"
        const val OPTIMIZER_INBOUND_TAG = "auto-optimizer-in"
        const val OPTIMIZER_SOCKS_PORT = 2080
        const val REMOTE_DNS_TAG = "remote"
        const val REMOTE_DNS_ADDRESS = "https://1.1.1.1/dns-query"
        const val LOCAL_DNS_TAG = "local"
        const val LOCAL_DNS_ADDRESS = "https://8.8.8.8/dns-query"
        const val BLOCK_DNS_TAG = "block"
    }
}
