package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.database.dao.HealthDao
import com.lisvpn.android.core.database.entity.HealthSnapshotEntity
import com.lisvpn.android.core.domain.model.HealthScore
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Security
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.Transport
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.math.abs
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
            val result = withTimeoutOrNull(PROTOCOL_PROBE_TIMEOUT_MS) {
                protocolProbe.probe(server)
            } ?: ProtocolProbeResult(
                snapshot = failedSnapshot(server),
                downloadedBytes = 0L,
                downloadMs = null,
                bytesPerSecond = null,
            ).also {
                Timber.w("Manual protocol validation timed out: server=%s timeoutMs=%d", server.displayName, PROTOCOL_PROBE_TIMEOUT_MS)
            }
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

    override suspend fun rank(servers: List<Server>, limit: Int): List<Server> = withContext(ioDispatcher) {
        if (servers.isEmpty() || limit <= 0) return@withContext emptyList()

        val selectedLimit = limit.coerceAtMost(AUTO_SELECTED_LIMIT)
        Timber.i("Auto ranking started: servers=%d limit=%d selectedLimit=%d", servers.size, limit, selectedLimit)

        val historicalScores = _scores.value.associateBy { it.serverId }
        val firstPass = probeAll(servers)
        val reachable = firstPass
            .filter { it.isReachable }
            .sortedByQuality()
        Timber.i(
            "Auto TCP filter: total=%d reachable=%d failed=%d top=%s",
            servers.size,
            reachable.size,
            firstPass.size - reachable.size,
            reachable.take(AUTO_LOG_CANDIDATE_LIMIT).joinToString { it.diagnosticLabel() },
        )
        if (reachable.isEmpty()) {
            Timber.w("Auto ranking stopped: no TCP reachable candidates")
            return@withContext emptyList()
        }

        val protocolCandidates = selectStage1Candidates(
            reachable = reachable,
            limit = STAGE1_CANDIDATE_LIMIT.coerceAtMost(reachable.size),
            scores = historicalScores,
        )
        Timber.i(
            "Auto scoring started: strategy=protocol stage1Candidates=%d selectedTcp=%s",
            protocolCandidates.size,
            protocolCandidates.joinToString { it.diagnosticLabel() },
        )
        val protocolResults = protocolProbeAll(protocolCandidates.map { it.server })
        val scored = protocolResults
            .map { it.toScoredCandidate() }
            .sortedByProtocolScore()
        val working = scored.filter { it.result.snapshot.success }
        Timber.i(
            "Auto protocol scoring completed: candidates=%d working=%d selected=%s",
            protocolCandidates.size,
            working.size,
            working.take(selectedLimit).joinToString { it.diagnosticLabel() },
        )
        if (working.isEmpty()) {
            Timber.w("Auto ranking stopped: TCP candidates did not pass protocol validation")
            return@withContext emptyList()
        }
        working.take(selectedLimit).map { it.server }
    }

    private suspend fun probeAll(servers: List<Server>): List<ProbeCandidate> = coroutineScope {
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        servers.mapIndexed { index, server ->
            async {
                semaphore.withPermit {
                    ProbeCandidate(server, index, listOf(tcpProbeOnce(server)))
                }
            }
        }.awaitAll()
    }

    private suspend fun protocolProbeAll(servers: List<Server>): List<ProtocolCandidate> = coroutineScope {
        val semaphore = Semaphore(PROTOCOL_PROBE_CONCURRENCY)
        servers.map { server ->
            async {
                semaphore.withPermit {
                    val result = try {
                        withTimeoutOrNull(PROTOCOL_PROBE_TIMEOUT_MS) {
                            protocolProbe.probe(server)
                        } ?: ProtocolProbeResult(
                            snapshot = failedSnapshot(server),
                            downloadedBytes = 0L,
                            downloadMs = null,
                            bytesPerSecond = null,
                        ).also {
                            Timber.w("Protocol probe timed out: server=%s timeoutMs=%d", server.displayName, PROTOCOL_PROBE_TIMEOUT_MS)
                        }
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
            }
        }.awaitAll()
    }

    private suspend fun verifyCandidates(candidates: List<ProbeCandidate>): List<ProbeCandidate> = coroutineScope {
        val semaphore = Semaphore(VERIFY_CONCURRENCY)
        candidates.map { candidate ->
            async {
                semaphore.withPermit {
                    val extraSnapshots = mutableListOf<HealthSnapshot>()
                    for (attempt in 1 until VERIFY_ROUNDS) {
                        extraSnapshots += tcpProbeOnce(candidate.server)
                    }
                    candidate.copy(snapshots = candidate.snapshots + extraSnapshots)
                }
            }
        }.awaitAll()
    }

    private suspend fun tcpProbeOnce(server: Server): HealthSnapshot {
        return try {
            val snapshot = healthProbe.probe(server)
            record(snapshot)
            snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "TCP health probe crashed: server=%s", server.displayName)
            failedSnapshot(server)
        }
    }

    private fun List<ProbeCandidate>.sortedByQuality(): List<ProbeCandidate> =
        sortedWith(
            compareByDescending<ProbeCandidate> { it.successCount }
                .thenBy { it.averageTcpMs ?: Int.MAX_VALUE }
                .thenBy { it.bestTcpMs ?: Int.MAX_VALUE }
                .thenBy { it.originalIndex }
                .thenBy { it.server.displayName.lowercase() },
        )

    private fun selectStage1Candidates(
        reachable: List<ProbeCandidate>,
        limit: Int,
        scores: Map<String, HealthScore>,
    ): List<ProbeCandidate> {
        if (limit <= 0) return emptyList()
        val selected = linkedMapOf<String, ProbeCandidate>()
        fun add(candidate: ProbeCandidate): Boolean {
            if (selected.size >= limit || selected.containsKey(candidate.server.id)) return false
            selected[candidate.server.id] = candidate
            return true
        }
        var preferredAdded = 0
        reachable
            .filter { candidate ->
                candidate.server.tags.any { it == Server.Tag.Primary || it == Server.Tag.MobileBypass } ||
                    ((scores[candidate.server.id]?.successRate ?: 0f) >= HISTORICAL_SUCCESS_THRESHOLD)
            }
            .sortedWith(
                compareByDescending<ProbeCandidate> { scores[it.server.id]?.score ?: 0f }
                    .thenBy { it.averageTcpMs ?: Int.MAX_VALUE }
                    .thenBy { it.originalIndex },
            )
            .forEach { candidate ->
                if (preferredAdded < PREFERRED_CANDIDATE_LIMIT && add(candidate)) preferredAdded += 1
            }
        reachable.take(FASTEST_CANDIDATE_LIMIT).forEach(::add)
        reachable
            .groupBy { it.diversityKey() }
            .values
            .mapNotNull { group -> group.sortedByQuality().firstOrNull() }
            .sortedByQuality()
            .forEach(::add)
        val spreadStep = (reachable.size / SPREAD_CANDIDATE_LIMIT).coerceAtLeast(1)
        reachable
            .sortedBy { it.originalIndex }
            .filterIndexed { index, _ -> index % spreadStep == 0 }
            .forEach(::add)
        reachable.forEach(::add)
        return selected.values.toList()
    }

    private fun ProtocolCandidate.toScoredCandidate(): ScoredCandidate =
        ScoredCandidate(server = server, result = result, score = adaptiveScore())

    private fun List<ScoredCandidate>.sortedByProtocolScore(): List<ScoredCandidate> =
        sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenByDescending { it.result.bytesPerSecond ?: 0L }
                .thenBy { it.result.snapshot.httpRttMs ?: Int.MAX_VALUE }
                .thenBy { it.server.displayName.lowercase() },
        )

    private fun ProtocolCandidate.adaptiveScore(): Double {
        val weights = result.snapshot.networkType.weights()
        val throughputScore = result.bytesPerSecond
            ?.let { (it.toDouble() / MAX_SCORE_BYTES_PER_SECOND.toDouble()).coerceIn(0.0, 1.0) }
            ?: if (result.snapshot.success) LIGHTWEIGHT_THROUGHPUT_NEUTRAL_SCORE else 0.0
        val latencyScore = inverseScore(result.snapshot.httpRttMs, MAX_SCORE_HTTP_RTT_MS)
        val startupScore = inverseScore(result.startupMs, MAX_SCORE_STARTUP_MS)
        val internetScore = ratio(result.internetSuccessCount, result.internetCheckCount)
        val blockedScore = if (result.blockedCheckCount > 0) ratio(result.blockedSuccessCount, result.blockedCheckCount) else 0.5
        val jitterScore = result.latencySamplesMs.jitterScore()
        val stabilityScore = ((internetScore * 0.55) + (jitterScore * 0.30) + (startupScore * 0.15)).coerceIn(0.0, 1.0)
        return (
            throughputScore * weights.throughput +
                latencyScore * weights.latency +
                stabilityScore * weights.stability +
                blockedScore * weights.blocked +
                startupScore * weights.startup
            ).coerceIn(0.0, 1.0)
    }

    private fun HealthSnapshot.NetworkType.weights(): ScoreWeights = when (this) {
        HealthSnapshot.NetworkType.Cellular,
        HealthSnapshot.NetworkType.CellularMetered,
        HealthSnapshot.NetworkType.CellularRoaming,
        HealthSnapshot.NetworkType.Metered -> ScoreWeights(throughput = 0.16, latency = 0.10, stability = 0.30, blocked = 0.34, startup = 0.10)
        HealthSnapshot.NetworkType.Wifi,
        HealthSnapshot.NetworkType.WifiMetered,
        HealthSnapshot.NetworkType.Ethernet -> ScoreWeights(throughput = 0.38, latency = 0.22, stability = 0.20, blocked = 0.12, startup = 0.08)
        HealthSnapshot.NetworkType.VpnInterface,
        HealthSnapshot.NetworkType.Unknown -> ScoreWeights(throughput = 0.28, latency = 0.18, stability = 0.26, blocked = 0.20, startup = 0.08)
    }

    private fun inverseScore(value: Int?, max: Int): Double {
        if (value == null) return 0.0
        return 1.0 - (value.coerceAtMost(max).toDouble() / max.toDouble())
    }

    private fun ratio(success: Int, total: Int): Double =
        if (total <= 0) 0.0 else (success.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)

    private fun List<Int>.jitterScore(): Double {
        if (isEmpty()) return 0.0
        if (size == 1) return 0.75
        val averageDelta = zipWithNext { a, b -> abs(a - b) }.average()
        return 1.0 - (averageDelta.coerceAtMost(MAX_SCORE_JITTER_MS.toDouble()) / MAX_SCORE_JITTER_MS.toDouble())
    }

    private fun ProbeCandidate.diagnosticLabel(): String {
        val tcpMs = averageTcpMs ?: bestTcpMs
        return if (tcpMs != null) {
            "${server.displayName}/tcp=${tcpMs}ms"
        } else {
            "${server.displayName}/tcp=n/a"
        }
    }

    private fun ScoredCandidate.diagnosticLabel(): String {
        val speedKbps = (result.bytesPerSecond ?: 0L) * 8L / 1_000L
        val packetLoss = if (result.internetCheckCount > 0) {
            100 - ((result.internetSuccessCount * 100) / result.internetCheckCount)
        } else {
            100
        }
        return "${server.displayName}/score=${(score * 100).roundToInt()}/http=${result.snapshot.httpRttMs}ms/speed=${speedKbps}kbps/loss=${packetLoss}%/blocked=${result.blockedSuccessCount}/${result.blockedCheckCount}"
    }

    private fun ProbeCandidate.diversityKey(): String =
        "${server.outbound.protocol}:${server.outbound.transportKey()}:${server.outbound.securityKey()}:${server.outbound.host}:${server.outbound.port}"

    private fun Outbound.transportKey(): String = when (this) {
        is Outbound.Vless -> transport.key()
        is Outbound.Vmess -> transport.key()
        is Outbound.Trojan -> transport.key()
        is Outbound.Shadowsocks -> "shadowsocks"
    }

    private fun Transport.key(): String = when (this) {
        Transport.Tcp -> "tcp"
        is Transport.WebSocket -> "ws:${host.orEmpty()}:${path.orEmpty()}"
        is Transport.Grpc -> "grpc:$serviceName"
        is Transport.HttpUpgrade -> "httpupgrade:${host.orEmpty()}:${path.orEmpty()}"
        is Transport.XHttp -> "xhttp:${host.orEmpty()}:${path.orEmpty()}:${mode.orEmpty()}"
    }

    private fun Outbound.securityKey(): String = when (this) {
        is Outbound.Vless -> security.key()
        is Outbound.Vmess -> security.key()
        is Outbound.Trojan -> security.key()
        is Outbound.Shadowsocks -> "none"
    }

    private fun Security.key(): String = when (this) {
        Security.None -> "none"
        is Security.Tls -> "tls:${sni.orEmpty()}"
        is Security.Reality -> "reality:$sni"
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

    private data class ProbeCandidate(
        val server: Server,
        val originalIndex: Int,
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

    private data class ScoredCandidate(
        val server: Server,
        val result: ProtocolProbeResult,
        val score: Double,
    )

    private data class ScoreWeights(
        val throughput: Double,
        val latency: Double,
        val stability: Double,
        val blocked: Double,
        val startup: Double,
    )

    private companion object {
        const val PROBE_CONCURRENCY = 10
        const val VERIFY_CONCURRENCY = 8
        const val PROTOCOL_PROBE_CONCURRENCY = 1
        const val STAGE1_CANDIDATE_LIMIT = 12
        const val AUTO_SELECTED_LIMIT = 10
        const val PREFERRED_CANDIDATE_LIMIT = 4
        const val FASTEST_CANDIDATE_LIMIT = 6
        const val SPREAD_CANDIDATE_LIMIT = 4
        const val PROTOCOL_PROBE_TIMEOUT_MS = 16_000L
        const val VERIFY_CANDIDATE_LIMIT = 6
        const val VERIFY_ROUNDS = 3
        const val MAX_TOTAL_SAMPLES = 500
        const val MAX_SAMPLES_PER_SERVER = 12
        const val MAX_SCORE_PING_MS = 1_500
        const val MAX_SCORE_HTTP_RTT_MS = 2_500
        const val MAX_SCORE_STARTUP_MS = 5_000
        const val MAX_SCORE_JITTER_MS = 800
        const val MAX_SCORE_BYTES_PER_SECOND = 2_000_000L
        const val LIGHTWEIGHT_THROUGHPUT_NEUTRAL_SCORE = 0.5
        const val HISTORICAL_SUCCESS_THRESHOLD = 0.75f
        const val AUTO_LOG_CANDIDATE_LIMIT = 16
    }
}
