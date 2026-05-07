package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.database.dao.HealthDao
import com.lisvpn.android.core.database.entity.HealthSnapshotEntity
import com.lisvpn.android.core.domain.model.HealthScore
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.vpn.health.HealthProbe
import com.lisvpn.android.vpn.health.ProtocolProbe
import com.lisvpn.android.vpn.health.ProtocolProbeResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * MVP stub — no probing yet. Returns input servers in original order so SelectBestServerUseCase
 * still produces a deterministic ranking.
 */
@Singleton
class ServerHealthRepositoryImpl @Inject constructor(
    private val healthDao: HealthDao,
    private val healthProbe: HealthProbe,
    private val protocolProbe: ProtocolProbe,
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
            val snapshot = healthProbe.probe(server)
            record(snapshot)
            AppResult.Success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "Health probe crashed: server=%s", server.displayName)
            AppResult.Failure(AppError.from(e), e)
        }
    }

    override suspend fun rank(servers: List<Server>, limit: Int): List<Server> = withContext(ioDispatcher) {
        if (servers.isEmpty() || limit <= 0) return@withContext emptyList()

        // Phase 1: fast TCP filter
        val firstPass = probeAll(servers)
        val reachable = firstPass
            .filter { it.isReachable }
            .sortedByQuality()
        Timber.i(
            "Auto TCP filter: total=%d reachable=%d",
            servers.size, reachable.size,
        )

        // Phase 2: real protocol probe (download test) for top candidates
        if (PROTOCOL_PROBE_ENABLED) {
            val candidateCount = maxOf(PROTOCOL_PROBE_CANDIDATE_LIMIT, limit)
            val protocolResults = protocolProbeAll(reachable.take(candidateCount).map { it.server })
            val working = protocolResults
                .filter { it.result.snapshot.success }
                .sortedWith(
                    compareBy<ProtocolCandidate> { it.result.snapshot.httpRttMs ?: Int.MAX_VALUE }
                        .thenByDescending { it.result.bytesPerSecond ?: 0L }
                        .thenBy { it.server.displayName.lowercase() }
                )
            Timber.i(
                "Auto protocol probe: candidates=%d working=%d selected=%s",
                candidateCount.coerceAtMost(reachable.size),
                working.size,
                working.take(limit).joinToString { c ->
                    "${c.server.displayName}/httpRtt=${c.result.snapshot.httpRttMs}ms/speed=${(c.result.bytesPerSecond ?: 0) * 8 / 1000}kbps"
                },
            )
            if (working.isNotEmpty()) {
                return@withContext working.take(limit).map { it.server }
            }
        }

        // Fallback: if no protocol probe passed, use TCP-verified candidates
        Timber.w("Protocol probe disabled or no servers passed, falling back to TCP-verified")
        val verified = verifyCandidates(reachable.take(maxOf(VERIFY_CANDIDATE_LIMIT, limit)))
            .sortedByQuality()
        verified.take(limit).map { it.server }
    }

    private suspend fun probeAll(servers: List<Server>): List<ProbeCandidate> = coroutineScope {
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        servers.map { server ->
            async {
                semaphore.withPermit {
                    ProbeCandidate(server, listOf(probeOnce(server)))
                }
            }
        }.awaitAll()
    }

    private suspend fun protocolProbeAll(servers: List<Server>): List<ProtocolCandidate> = coroutineScope {
        servers.map { server ->
            async {
                val result = try {
                    protocolProbe.probe(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.w(e, "Protocol probe crashed: server=%s", server.displayName)
                    ProtocolProbeResult(
                        snapshot = failedSnapshot(server),
                        downloadedBytes = 0L,
                        downloadMs = null,
                        bytesPerSecond = null,
                    )
                }
                record(result.snapshot)
                ProtocolCandidate(server, result)
            }
        }.awaitAll()
    }

    private suspend fun verifyCandidates(candidates: List<ProbeCandidate>): List<ProbeCandidate> = coroutineScope {
        candidates.map { candidate ->
            async {
                val extraSnapshots = mutableListOf<HealthSnapshot>()
                for (attempt in 1 until VERIFY_ROUNDS) {
                    extraSnapshots += probeOnce(candidate.server)
                }
                candidate.copy(snapshots = candidate.snapshots + extraSnapshots)
            }
        }.awaitAll()
    }

    private suspend fun probeOnce(server: Server): HealthSnapshot = when (val result = probe(server)) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> failedSnapshot(server)
    }

    private fun List<ProbeCandidate>.sortedByQuality(): List<ProbeCandidate> =
        sortedWith(
            compareByDescending<ProbeCandidate> { it.successCount }
                .thenBy { it.averageTcpMs ?: Int.MAX_VALUE }
                .thenBy { it.bestTcpMs ?: Int.MAX_VALUE }
                .thenBy { it.server.displayName.lowercase() },
        )

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

    private data class ProbeCandidate(
        val server: Server,
        val snapshots: List<HealthSnapshot>,
    ) {
        private val successfulTcpMs: List<Int>
            get() = snapshots.filter { it.success }.mapNotNull { it.tcpHandshakeMs }

        val isReachable: Boolean
            get() = successfulTcpMs.isNotEmpty()

        val successCount: Int
            get() = successfulTcpMs.size

        val averageTcpMs: Int?
            get() = successfulTcpMs.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

        val bestTcpMs: Int?
            get() = successfulTcpMs.minOrNull()
    }

    private data class ProtocolCandidate(
        val server: Server,
        val result: ProtocolProbeResult,
    )

    private companion object {
        const val PROBE_CONCURRENCY = 10
        const val PROTOCOL_PROBE_CANDIDATE_LIMIT = 4
        const val PROTOCOL_PROBE_ENABLED = false
        const val VERIFY_CANDIDATE_LIMIT = 6
        const val VERIFY_ROUNDS = 3
        const val MAX_TOTAL_SAMPLES = 500
        const val MAX_SAMPLES_PER_SERVER = 12
        const val MAX_SCORE_PING_MS = 1_500
    }
}
