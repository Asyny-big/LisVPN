package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.database.dao.HealthDao
import com.lisvpn.android.core.database.entity.HealthSnapshotEntity
import com.lisvpn.android.core.domain.model.HealthScore
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.isGeneralVpnEligible
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.vpn.health.ProtocolProbe
import com.lisvpn.android.vpn.health.ProtocolProbeResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Stores health samples and provides the AUTO bootstrap order. Heavy optimization is intentionally
 * deferred until after the main VPN tunnel is connected.
 */
@Singleton
class ServerHealthRepositoryImpl @Inject constructor(
    private val healthDao: HealthDao,
    private val protocolProbe: ProtocolProbe,
    private val autoServerPreferenceStore: AutoServerPreferenceStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ServerHealthRepository {

    private val _scores = MutableStateFlow<List<HealthScore>>(emptyList())
    private val snapshotsMutex = Mutex()
    private val snapshots = mutableListOf<HealthSnapshot>()

    override fun observeScores(): Flow<List<HealthScore>> = _scores

    override fun observeScore(serverId: String): Flow<HealthScore?> =
        _scores.map { list -> list.firstOrNull { it.serverId == serverId } }

    override suspend fun record(snapshot: HealthSnapshot) {
        try {
            healthDao.insert(snapshot.toEntity())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "Failed to persist health snapshot")
        }
        snapshotsMutex.withLock {
            snapshots.add(snapshot)
            val overflow = snapshots.size - MAX_TOTAL_SAMPLES
            if (overflow > 0) repeat(overflow) { snapshots.removeAt(0) }
            _scores.value = computeScores(snapshots)
        }
    }

    override suspend fun probe(server: Server): AppResult<HealthSnapshot> {
        return try {
            // Real-world Android networks regularly drop the very first SYN to a fresh server
            // (radio sleep, captive-portal NAT, etc.). One immediate retry costs ~50 ms when the
            // server is healthy and saves the user from a confusing "server unreachable" toast.
            val result = probeWithRetry(server)
            val snapshot = result.snapshot
            record(snapshot)
            if (snapshot.success) {
                Timber.i(
                    "Manual protocol validation success: server=%s httpRtt=%sms speedKbps=%d blocked=%d/%d",
                    server.displayName,
                    snapshot.httpRttMs,
                    (result.bytesPerSecond ?: 0L) * 8L / 1_000L,
                    result.blockedSuccessCount,
                    result.blockedCheckCount,
                )
            } else {
                Timber.w(
                    "Manual protocol validation failed: server=%s httpRtt=%s downloadBytes=%d blocked=%d/%d",
                    server.displayName,
                    snapshot.httpRttMs,
                    result.downloadedBytes,
                    result.blockedSuccessCount,
                    result.blockedCheckCount,
                )
            }
            AppResult.Success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "Protocol validation crashed: server=%s", server.displayName)
            AppResult.Failure(AppError.from(e), e)
        }
    }

    private suspend fun probeWithRetry(server: Server): ProtocolProbeResult {
        val attempts = MANUAL_PROBE_ATTEMPTS.coerceAtLeast(1)
        var last: ProtocolProbeResult? = null
        for (attempt in 1..attempts) {
            val result = withTimeoutOrNull(PROTOCOL_PROBE_TIMEOUT_MS) {
                protocolProbe.probe(server)
            }
            if (result == null) {
                Timber.w(
                    "Manual protocol validation timed out: server=%s attempt=%d/%d timeoutMs=%d",
                    server.displayName,
                    attempt,
                    attempts,
                    PROTOCOL_PROBE_TIMEOUT_MS,
                )
            } else {
                if (result.snapshot.success) return result
                last = result
                Timber.w(
                    "Manual protocol validation attempt failed: server=%s attempt=%d/%d httpRtt=%s",
                    server.displayName,
                    attempt,
                    attempts,
                    result.snapshot.httpRttMs,
                )
            }
            if (attempt < attempts) {
                kotlinx.coroutines.delay(MANUAL_PROBE_RETRY_DELAY_MS)
            }
        }
        return last ?: ProtocolProbeResult(
            snapshot = failedSnapshot(server),
            downloadedBytes = 0L,
            downloadMs = null,
            bytesPerSecond = null,
        )
    }

    override suspend fun rank(servers: List<Server>, limit: Int): List<Server> = withContext(ioDispatcher) {
        if (servers.isEmpty() || limit <= 0) return@withContext emptyList()

        val candidates = servers.filter { it.isGeneralVpnEligible() }
        val excludedSpecial = servers.size - candidates.size
        val selectedLimit = limit.coerceAtMost(candidates.size)
        if (candidates.isEmpty()) {
            Timber.w(
                "Auto bootstrap plan skipped: total=%d excludedSpecial=%d",
                servers.size,
                servers.size,
            )
            return@withContext emptyList()
        }
        val profile = autoServerPreferenceStore.currentProfile()
        val cachedServerIds = autoServerPreferenceStore.cachedServerIds(profile)
        val scoreByServer = _scores.value.associateBy { it.serverId }
        val selected = candidates
            .bootstrapSorted(profile = profile, cachedServerIds = cachedServerIds, scores = scoreByServer)
            .take(selectedLimit)
        Timber.i(
            "Auto bootstrap plan: strategy=cache-stable-selector network=%s fingerprint=%s total=%d general=%d excludedSpecial=%d selected=%d bootstrap=%s cached=%s candidates=%s",
            profile.networkClass,
            profile.fingerprint,
            servers.size,
            candidates.size,
            excludedSpecial,
            selected.size,
            selected.firstOrNull()?.displayName,
            cachedServerIds.joinToString(),
            selected.take(AUTO_LOG_CANDIDATE_LIMIT).joinToString { it.bootstrapDiagnostic(scoreByServer[it.id]) },
        )
        selected
    }

    private fun List<Server>.bootstrapSorted(
        profile: AutoNetworkProfile,
        cachedServerIds: List<String>,
        scores: Map<String, HealthScore>,
    ): List<Server> {
        val originalIndex = withIndex().associate { it.value.id to it.index }
        return sortedWith(
            compareByDescending<Server> { server -> server.bootstrapPriority(profile, cachedServerIds, scores[server.id]) }
                .thenBy { server -> originalIndex[server.id] ?: Int.MAX_VALUE }
                .thenBy { server -> server.displayName.lowercase() },
        )
    }

    private fun Server.bootstrapPriority(
        profile: AutoNetworkProfile,
        cachedServerIds: List<String>,
        score: HealthScore?,
    ): Double {
        var priority = 0.0
        val cacheIndex = cachedServerIds.indexOf(id)
        if (cacheIndex >= 0) priority += 1_000.0 - (cacheIndex * 25.0)
        if (Server.Tag.Primary in tags) priority += 140.0
        if (Server.Tag.MobileBypass in tags) priority += if (profile.isMobileLike) 220.0 else 80.0
        if (Server.Tag.FastEdge in tags && !profile.isMobileLike) priority += 70.0
        if (Server.Tag.Backup in tags) priority -= 25.0
        priority += stableBootstrapScore()
        if (score != null) {
            priority += score.score * 120.0
            priority += score.successRate * if (profile.isMobileLike) 160.0 else 90.0
            priority -= (score.avgPingMs ?: MAX_SCORE_PING_MS).coerceAtMost(MAX_SCORE_PING_MS).toDouble() / 40.0
        }
        return priority
    }

    private fun Server.stableBootstrapScore(): Double {
        val country = countryCode.orEmpty().uppercase()
        val label = displayName.lowercase()
        return when {
            country == "EE" || label.contains("estonia") || label.contains("tallinn") -> 130.0
            country in STABLE_BOOTSTRAP_COUNTRIES -> 95.0
            label.contains("finland") || label.contains("netherlands") || label.contains("germany") -> 80.0
            else -> 0.0
        }
    }

    private fun Server.bootstrapDiagnostic(score: HealthScore?): String =
        buildString {
            append(displayName)
            append("/country=")
            append(countryCode ?: "n/a")
            if (score != null) {
                append("/hist=")
                append((score.score * 100).roundToInt())
                append("/success=")
                append((score.successRate * 100).roundToInt())
                append('%')
            }
        }

    private fun failedSnapshot(server: Server): HealthSnapshot = HealthSnapshot(
        serverId = server.id,
        timestamp = Clock.System.now(),
        tcpHandshakeMs = null,
        tlsHandshakeMs = null,
        httpRttMs = null,
        success = false,
        networkType = HealthSnapshot.NetworkType.Unknown,
    )

    private fun HealthSnapshot.toEntity(): HealthSnapshotEntity = HealthSnapshotEntity(
        serverId = serverId,
        timestampMs = timestamp.toEpochMilliseconds(),
        tcpHandshakeMs = tcpHandshakeMs,
        tlsHandshakeMs = tlsHandshakeMs,
        httpRttMs = httpRttMs,
        success = success,
        networkType = networkType.name,
    )

    private fun computeScores(allSnapshots: List<HealthSnapshot>): List<HealthScore> {
        val now = Clock.System.now()
        return allSnapshots
            .groupBy { it.serverId }
            .map { (serverId, serverSnapshots) ->
                val recent = serverSnapshots.takeLast(MAX_SAMPLES_PER_SERVER)
                val successful = recent.filter { it.success }
                val avgPing = successful
                    .mapNotNull { it.tcpHandshakeMs ?: it.httpRttMs ?: it.tlsHandshakeMs }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.roundToInt()
                val successRate = if (recent.isEmpty()) 0f else successful.size.toFloat() / recent.size.toFloat()
                val latencyScore = avgPing?.let { ping ->
                    1f - (ping.coerceAtMost(MAX_SCORE_PING_MS).toFloat() / MAX_SCORE_PING_MS.toFloat())
                } ?: 0f
                HealthScore(
                    serverId = serverId,
                    score = (successRate * 0.7f) + (latencyScore * 0.3f),
                    avgPingMs = avgPing,
                    successRate = successRate,
                    sampleCount = recent.size,
                    computedAt = now,
                )
            }
            .sortedWith(
                compareByDescending<HealthScore> { it.score }
                    .thenBy { it.avgPingMs ?: Int.MAX_VALUE },
            )
    }

    private companion object {
        const val AUTO_SELECTED_LIMIT = 32
        const val PROTOCOL_PROBE_TIMEOUT_MS = 16_000L
        const val MANUAL_PROBE_ATTEMPTS = 2
        const val MANUAL_PROBE_RETRY_DELAY_MS = 250L
        const val MAX_TOTAL_SAMPLES = 500
        const val MAX_SAMPLES_PER_SERVER = 12
        const val MAX_SCORE_PING_MS = 1_500
        const val AUTO_LOG_CANDIDATE_LIMIT = 16
        val STABLE_BOOTSTRAP_COUNTRIES = setOf("EE", "FI", "NL", "DE", "LV", "LT", "PL")
    }
}
