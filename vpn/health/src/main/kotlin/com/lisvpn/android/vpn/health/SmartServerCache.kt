package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.database.dao.SmartServerCacheDao
import com.lisvpn.android.core.database.entity.SmartServerCacheEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Singleton
class SmartServerCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SmartServerCacheDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun currentProfile(): SmartNetworkProfile = withContext(ioDispatcher) {
        detectNetworkProfile()
    }

    suspend fun histories(
        profile: SmartNetworkProfile,
        serverIds: List<String>,
    ): Map<String, SmartServerHistory> = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext emptyMap()
        dao.histories(profile.networkKey, serverIds)
            .associate { it.serverId to it.toHistory() }
    }

    suspend fun bestServerIds(profile: SmartNetworkProfile, limit: Int): List<String> =
        withContext(ioDispatcher) {
            dao.bestForNetwork(profile.networkKey, limit.coerceAtLeast(1)).map { it.serverId }
        }

    suspend fun record(profile: SmartNetworkProfile, scored: ScoredAutoServer) =
        withContext(ioDispatcher) {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val previous = dao.history(profile.networkKey, scored.server.id)
            val success = scored.eligible
            val successCount = (previous?.successCount ?: 0) + if (success) 1 else 0
            val failureCount = (previous?.failureCount ?: 0) + if (success) 0 else 1
            val entity = SmartServerCacheEntity(
                networkKey = profile.networkKey,
                serverId = scored.server.id,
                networkClass = profile.networkClass.name,
                networkFingerprint = profile.fingerprint,
                mobileOperator = profile.mobileOperator,
                asn = profile.asn,
                lastScore = if (success) scored.score else ((previous?.lastScore ?: 0.0) * FAILURE_SCORE_DECAY),
                successCount = successCount,
                failureCount = failureCount,
                lastLatencyMs = scored.validation.averageRttMs ?: previous?.lastLatencyMs,
                lastThroughputKbps = scored.throughput?.kbps ?: previous?.lastThroughputKbps,
                lastJitterMs = scored.validation.jitterMs ?: previous?.lastJitterMs,
                lastPacketLoss = scored.validation.packetLossApprox,
                telegramReachable = scored.validation.telegramReachable,
                youtubeReachable = scored.validation.youtubeReachable,
                lastSuccessAtMs = if (success) nowMs else previous?.lastSuccessAtMs,
                updatedAtMs = nowMs,
            )
            dao.upsert(entity)
            dao.pruneOlderThan(nowMs - CACHE_TTL_MS)
        }

    private fun detectNetworkProfile(): SmartNetworkProfile {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val activeCaps = active?.let(manager::getNetworkCapabilities)
        val caps = if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            activeCaps
        } else {
            manager.allNetworks
                .asSequence()
                .mapNotNull(manager::getNetworkCapabilities)
                .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                .maxByOrNull { it.profilePriority() }
                ?: activeCaps
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val carrier = context.telephonyCarrierName()
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> SmartNetworkProfile(
                networkClass = SmartNetworkClass.Mobile,
                fingerprint = "mobile:${carrier.ifBlank { "unknown" }}".safeFingerprint(),
                mobileOperator = carrier.ifBlank { null },
                asn = null,
            )
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> SmartNetworkProfile(
                networkClass = SmartNetworkClass.Wifi,
                fingerprint = "wifi:${if (metered) "metered" else "unmetered"}:${context.wifiFingerprintPart().ifBlank { "unknown" }}".safeFingerprint(),
                mobileOperator = null,
                asn = null,
            )
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> SmartNetworkProfile(
                networkClass = SmartNetworkClass.Ethernet,
                fingerprint = "ethernet",
                mobileOperator = null,
                asn = null,
            )
            metered -> SmartNetworkProfile(
                networkClass = SmartNetworkClass.Metered,
                fingerprint = "metered",
                mobileOperator = carrier.ifBlank { null },
                asn = null,
            )
            else -> SmartNetworkProfile(
                networkClass = SmartNetworkClass.Unknown,
                fingerprint = "unknown",
                mobileOperator = carrier.ifBlank { null },
                asn = null,
            )
        }
    }

    private fun SmartServerCacheEntity.toHistory(): SmartServerHistory = SmartServerHistory(
        serverId = serverId,
        lastScore = lastScore,
        successCount = successCount,
        failureCount = failureCount,
        lastLatencyMs = lastLatencyMs,
        lastThroughputKbps = lastThroughputKbps,
        lastJitterMs = lastJitterMs,
        lastPacketLoss = lastPacketLoss,
        telegramReachable = telegramReachable,
        youtubeReachable = youtubeReachable,
        lastSuccessAtMs = lastSuccessAtMs,
    )

    private fun Context.telephonyCarrierName(): String =
        runCatching {
            val manager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            listOf(manager?.networkOperator.orEmpty(), manager?.networkOperatorName.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("-")
                .normalizeFingerprintPart()
        }.getOrDefault("")

    private fun Context.wifiFingerprintPart(): String =
        runCatching {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.connectionInfo?.ssid
                .orEmpty()
                .trim('"')
                .normalizeFingerprintPart()
        }.getOrDefault("")

    private fun String.normalizeFingerprintPart(): String =
        lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(64)

    private fun String.safeFingerprint(): String =
        MessageDigest.getInstance("SHA-1")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun NetworkCapabilities.profilePriority(): Int {
        val validation = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
        val internet = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 50 else 0
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 0
        }
        return validation + internet + transport
    }

    private companion object {
        const val FAILURE_SCORE_DECAY = 0.65
        const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
