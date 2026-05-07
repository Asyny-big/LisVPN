package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.security.SecretBox
import com.lisvpn.android.core.database.entity.ProfileEntity
import com.lisvpn.android.core.database.entity.ServerEntity
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Profile
import com.lisvpn.android.core.domain.model.ProfileSource
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun ProfileEntity.toDomain(): Profile = Profile(
    id = id,
    name = name,
    source = ProfileSourceDb.from(sourceType, sourceValue),
    expiresAt = expiresAtMs?.let(Instant::fromEpochMilliseconds),
    updateIntervalHours = updateIntervalHours,
    announceMessage = announceMessage,
    createdAt = Instant.fromEpochMilliseconds(createdAtMs),
    lastRefreshedAt = lastRefreshedAtMs?.let(Instant::fromEpochMilliseconds),
    isPrimary = isPrimary,
)

/**
 * Reads a [ServerEntity] row, transparently decrypting the sensitive columns written by newer
 * app versions while still accepting legacy plaintext rows produced before the SecretBox
 * migration (see [SecretBox.tryDecrypt]).
 */
internal fun ServerEntity.toDomain(secretBox: SecretBox): Server = Server(
    id = id,
    profileId = profileId,
    displayName = displayName,
    countryCode = countryCode,
    outbound = OutboundJson.decode(secretBox.tryDecrypt(outboundJson)),
    rawUri = secretBox.tryDecrypt(rawUri),
    tags = tagsCsv.split(',').mapNotNull { value -> value.takeIf { it.isNotBlank() }?.let { Server.Tag.valueOf(it) } }.toSet(),
    createdAt = Instant.fromEpochMilliseconds(createdAtMs),
)

/**
 * Serialises a domain [Server] into a [ServerEntity] row. The outbound JSON (uuid, password,
 * reality public key, ...) and the raw VLESS URI are encrypted at rest via [SecretBox].
 */
internal fun Server.toEntity(secretBox: SecretBox): ServerEntity = ServerEntity(
    id = id,
    profileId = profileId,
    displayName = displayName,
    countryCode = countryCode,
    outboundJson = secretBox.encrypt(OutboundJson.encode(outbound)),
    rawUri = secretBox.encrypt(rawUri),
    tagsCsv = tags.joinToString(",") { it.name },
    createdAtMs = createdAt.toEpochMilliseconds(),
)

internal object ProfileSourceDb {
    const val TYPE_SUBSCRIPTION_URL = "subscription_url"
    const val TYPE_SINGLE_URI = "single_uri"
    const val TYPE_JSON_CONFIG = "json_config"
    const val TYPE_LISVPN_ACCOUNT = "lisvpn_account"

    fun from(source: ProfileSource): Pair<String, String> = when (source) {
        is ProfileSource.SubscriptionUrl -> TYPE_SUBSCRIPTION_URL to source.url
        is ProfileSource.SingleUri -> TYPE_SINGLE_URI to source.uri
        is ProfileSource.JsonConfig -> TYPE_JSON_CONFIG to source.origin
        is ProfileSource.LisVpnAccount -> TYPE_LISVPN_ACCOUNT to source.accountId
    }

    fun from(type: String, value: String): ProfileSource = when (type) {
        TYPE_SUBSCRIPTION_URL -> ProfileSource.SubscriptionUrl(value)
        TYPE_SINGLE_URI -> ProfileSource.SingleUri(value)
        TYPE_JSON_CONFIG -> ProfileSource.JsonConfig(value)
        TYPE_LISVPN_ACCOUNT -> ProfileSource.LisVpnAccount(value)
        else -> ProfileSource.SubscriptionUrl(value)
    }
}

private object OutboundJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(outbound: Outbound): String = json.encodeToString(JsonObject.serializer(), outbound.toJsonObject())

    fun decode(value: String): Outbound {
        val root = json.parseToJsonElement(value).jsonObject
        return when (root.requiredString("protocol")) {
            "vless" -> Outbound.Vless(
                host = root.requiredString("host"),
                port = root.requiredInt("port"),
                uuid = root.requiredString("uuid"),
                flow = root.optionalString("flow"),
                encryption = root.optionalString("encryption") ?: "none",
                transport = decodeTransport(root.requiredObject("transport")),
                security = decodeSecurity(root.requiredObject("security")),
            )
            "vmess" -> Outbound.Vmess(
                host = root.requiredString("host"),
                port = root.requiredInt("port"),
                uuid = root.requiredString("uuid"),
                alterId = root.optionalInt("alterId") ?: 0,
                cipher = root.optionalString("cipher") ?: "auto",
                transport = decodeTransport(root.requiredObject("transport")),
                security = decodeSecurity(root.requiredObject("security")),
            )
            "trojan" -> Outbound.Trojan(
                host = root.requiredString("host"),
                port = root.requiredInt("port"),
                password = root.requiredString("password"),
                transport = decodeTransport(root.requiredObject("transport")),
                security = decodeSecurity(root.requiredObject("security")),
            )
            "shadowsocks" -> Outbound.Shadowsocks(
                host = root.requiredString("host"),
                port = root.requiredInt("port"),
                password = root.requiredString("password"),
                method = root.requiredString("method"),
            )
            else -> error("Unsupported outbound protocol")
        }
    }

    private fun Outbound.toJsonObject(): JsonObject = when (this) {
        is Outbound.Vless -> buildJsonObject {
            put("protocol", "vless")
            put("host", host)
            put("port", port)
            put("uuid", uuid)
            flow?.let { put("flow", it) }
            put("encryption", encryption)
            put("transport", transport.toJsonObject())
            put("security", security.toJsonObject())
        }
        is Outbound.Vmess -> buildJsonObject {
            put("protocol", "vmess")
            put("host", host)
            put("port", port)
            put("uuid", uuid)
            put("alterId", alterId)
            put("cipher", cipher)
            put("transport", transport.toJsonObject())
            put("security", security.toJsonObject())
        }
        is Outbound.Trojan -> buildJsonObject {
            put("protocol", "trojan")
            put("host", host)
            put("port", port)
            put("password", password)
            put("transport", transport.toJsonObject())
            put("security", security.toJsonObject())
        }
        is Outbound.Shadowsocks -> buildJsonObject {
            put("protocol", "shadowsocks")
            put("host", host)
            put("port", port)
            put("password", password)
            put("method", method)
        }
    }

    private fun Transport.toJsonObject(): JsonObject = when (this) {
        Transport.Tcp -> buildJsonObject { put("type", "tcp") }
        is Transport.WebSocket -> buildJsonObject {
            put("type", "ws")
            path?.let { put("path", it) }
            host?.let { put("host", it) }
            earlyDataHeader?.let { put("earlyDataHeader", it) }
        }
        is Transport.Grpc -> buildJsonObject {
            put("type", "grpc")
            put("serviceName", serviceName)
        }
        is Transport.HttpUpgrade -> buildJsonObject {
            put("type", "httpupgrade")
            path?.let { put("path", it) }
            host?.let { put("host", it) }
        }
        is Transport.XHttp -> buildJsonObject {
            put("type", "xhttp")
            path?.let { put("path", it) }
            host?.let { put("host", it) }
            mode?.let { put("mode", it) }
        }
    }

    private fun Security.toJsonObject(): JsonObject = when (this) {
        Security.None -> buildJsonObject { put("type", "none") }
        is Security.Tls -> buildJsonObject {
            put("type", "tls")
            sni?.let { put("sni", it) }
            putJsonArray("alpn") { alpn.forEach { add(it) } }
            fingerprint?.let { put("fingerprint", it) }
            put("allowInsecure", allowInsecure)
        }
        is Security.Reality -> buildJsonObject {
            put("type", "reality")
            put("sni", sni)
            put("publicKey", publicKey)
            shortId?.let { put("shortId", it) }
            fingerprint?.let { put("fingerprint", it) }
            spiderX?.let { put("spiderX", it) }
        }
    }

    private fun decodeTransport(value: JsonObject): Transport = when (value.requiredString("type")) {
        "tcp" -> Transport.Tcp
        "ws" -> Transport.WebSocket(
            path = value.optionalString("path"),
            host = value.optionalString("host"),
            earlyDataHeader = value.optionalString("earlyDataHeader"),
        )
        "grpc" -> Transport.Grpc(serviceName = value.requiredString("serviceName"))
        "httpupgrade" -> Transport.HttpUpgrade(path = value.optionalString("path"), host = value.optionalString("host"))
        "xhttp" -> Transport.XHttp(path = value.optionalString("path"), host = value.optionalString("host"), mode = value.optionalString("mode"))
        else -> Transport.Tcp
    }

    private fun decodeSecurity(value: JsonObject): Security = when (value.requiredString("type")) {
        "none" -> Security.None
        "tls" -> Security.Tls(
            sni = value.optionalString("sni"),
            alpn = value["alpn"]?.let { element -> element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.orEmpty(),
            fingerprint = value.optionalString("fingerprint"),
            allowInsecure = value.optionalBoolean("allowInsecure") ?: false,
        )
        "reality" -> Security.Reality(
            sni = value.requiredString("sni"),
            publicKey = value.requiredString("publicKey"),
            shortId = value.optionalString("shortId"),
            fingerprint = value.optionalString("fingerprint"),
            spiderX = value.optionalString("spiderX"),
        )
        else -> Security.None
    }

    private fun JsonObject.requiredObject(name: String): JsonObject = this[name]?.jsonObject ?: error("Missing $name")
    private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing $name")
    private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.requiredInt(name: String): Int = this[name]?.jsonPrimitive?.int ?: error("Missing $name")
    private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
}
