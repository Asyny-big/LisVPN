package com.lisvpn.android.core.data.repository

import android.os.SystemClock
import com.lisvpn.android.core.common.di.ApplicationScope
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.model.isGeneralVpnEligible
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.core.domain.repository.VpnRepository
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.math.abs
import kotlin.math.roundToInt
import timber.log.Timber

@Singleton
class AutoOptimizerRepositoryImpl @Inject constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val vpnRepository: VpnRepository,
    private val serverHealthRepository: ServerHealthRepository,
    private val autoServerPreferenceStore: AutoServerPreferenceStore,
) : AutoOptimizerRepository {

    private val mutex = Mutex()
    private var optimizerJob: Job? = null

    override fun schedule(servers: List<Server>) {
        optimizerJob?.cancel()
        if (servers.size <= 1) {
            Timber.i("Auto optimizer skipped: only one server")
            return
        }
        optimizerJob = applicationScope.launch {
            try {
                val connected = withTimeoutOrNull(CONNECTED_WAIT_TIMEOUT_MS) {
                    vpnRepository.state.first { it is VpnState.Connected }
                }
                if (connected == null) {
                    Timber.w("Auto optimizer skipped: VPN did not reach connected state")
                    return@launch
                }
                delay(POST_CONNECT_WARMUP_MS)
                optimize(servers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Auto optimizer failed")
            }
        }
    }

    override fun cancel() {
        optimizerJob?.cancel()
        optimizerJob = null
    }

    private suspend fun optimize(servers: List<Server>) = mutex.withLock {
        withContext(ioDispatcher) {
            val profile = autoServerPreferenceStore.currentProfile()
            val cachedServerIds = autoServerPreferenceStore.cachedServerIds(profile)
            val indexedServers = servers
                .mapIndexed { index, server -> OptimizationCandidate(server, "srv-$index") }
                .filter { it.server.isGeneralVpnEligible() }
            if (indexedServers.isEmpty()) {
                Timber.w("Auto optimizer skipped: no general-purpose candidates")
                return@withContext
            }
            val candidates = selectOptimizationCandidates(indexedServers, profile, cachedServerIds)
            val originalTag = indexedServers.first().outboundTag
            var optimizerTag = originalTag
            val results = mutableListOf<ScoredTunnelCandidate>()

            Timber.i(
                "Auto optimizer started: stage=inside-vpn network=%s fingerprint=%s total=%d candidates=%s",
                profile.networkClass,
                profile.fingerprint,
                servers.size,
                candidates.joinToString { it.server.displayName },
            )

            for (candidate in candidates) {
                if (vpnRepository.state.value !is VpnState.Connected) {
                    Timber.i("Auto optimizer stopped: VPN is no longer connected")
                    break
                }
                val tag = candidate.outboundTag
                val switchStartedAt = SystemClock.elapsedRealtime()
                val switched = vpnRepository.selectOutbound(AUTO_OPTIMIZER_SELECTOR_TAG, tag)
                if (switched is com.lisvpn.android.core.common.result.AppResult.Failure) {
                    Timber.w("Auto optimizer candidate skipped: switch failed server=%s tag=%s", candidate.server.displayName, tag)
                    continue
                }
                optimizerTag = tag
                delay(CANDIDATE_WARMUP_MS)

                val probeResult = withTimeoutOrNull(CANDIDATE_PROBE_TIMEOUT_MS) {
                    AutoTunnelProbe.probe(candidate.server, elapsedSince(switchStartedAt))
                } ?: AutoTunnelProbeResult(
                    startupMs = null,
                    dnsMs = null,
                    httpRttMs = null,
                    latencySamplesMs = emptyList(),
                    throughputBytesPerSecond = null,
                    internetCheckCount = 1,
                    internetSuccessCount = 0,
                    blockedCheckCount = 0,
                    blockedSuccessCount = 0,
                    stabilityCheckCount = 0,
                    stabilitySuccessCount = 0,
                ).also {
                    Timber.w(
                        "Auto optimizer candidate timed out: server=%s tag=%s timeoutMs=%d",
                        candidate.server.displayName,
                        tag,
                        CANDIDATE_PROBE_TIMEOUT_MS,
                    )
                }
                val score = probeResult.adaptiveScore(profile, candidate.server)
                results += ScoredTunnelCandidate(candidate.server, tag, score, probeResult)
                serverHealthRepository.record(probeResult.toHealthSnapshot(candidate.server))
                Timber.i("Auto optimizer candidate: %s", results.last().diagnosticLabel())
            }

            val best = results
                .filter { it.probe.successful }
                .sortedWith(
                    compareByDescending<ScoredTunnelCandidate> { it.score }
                        .thenByDescending { it.probe.throughputBytesPerSecond ?: 0L }
                        .thenBy { it.probe.httpRttMs ?: Int.MAX_VALUE },
                )
                .firstOrNull()

            if (best == null) {
                if (optimizerTag != originalTag) vpnRepository.selectOutbound(AUTO_OPTIMIZER_SELECTOR_TAG, originalTag)
                Timber.w("Auto optimizer finished: no successful candidate; kept live bootstrap=%s", originalTag)
                return@withContext
            }

            vpnRepository.selectOutbound(AUTO_SELECTOR_TAG, best.outboundTag)
            autoServerPreferenceStore.saveBest(profile, best.server, best.score)
            Timber.i(
                "Auto optimizer finished: best=%s tag=%s score=%d network=%s metrics=%s",
                best.server.displayName,
                best.outboundTag,
                (best.score * 100).roundToInt(),
                profile.networkClass,
                best.probe.metricsLabel(),
            )
        }
    }

    private fun selectOptimizationCandidates(
        indexedServers: List<OptimizationCandidate>,
        profile: AutoNetworkProfile,
        cachedServerIds: List<String>,
    ): List<OptimizationCandidate> {
        val selected = linkedMapOf<String, OptimizationCandidate>()
        fun add(candidate: OptimizationCandidate) {
            if (selected.size < OPTIMIZER_CANDIDATE_LIMIT) selected.putIfAbsent(candidate.server.id, candidate)
        }

        add(indexedServers.first())
        cachedServerIds.forEach { cachedId -> indexedServers.firstOrNull { it.server.id == cachedId }?.let(::add) }
        indexedServers
            .filter { Server.Tag.MobileBypass in it.server.tags || Server.Tag.Primary in it.server.tags }
            .sortedByDescending { if (profile.isMobileLike && Server.Tag.MobileBypass in it.server.tags) 2 else 1 }
            .forEach(::add)
        indexedServers.filter { it.server.stableBootstrapScore() > 0.0 }.forEach(::add)

        val spreadStep = (indexedServers.size / SPREAD_CANDIDATE_COUNT).coerceAtLeast(1)
        indexedServers.filterIndexed { index, _ -> index % spreadStep == 0 }.forEach(::add)
        indexedServers.forEach(::add)
        return selected.values.toList()
    }

    private fun AutoTunnelProbeResult.adaptiveScore(profile: AutoNetworkProfile, server: Server): Double {
        val weights = profile.weights()
        val throughputScore = throughputBytesPerSecond
            ?.let { (it.toDouble() / MAX_SCORE_BYTES_PER_SECOND.toDouble()).coerceIn(0.0, 1.0) }
            ?: 0.0
        val latencyScore = inverseScore(httpRttMs, MAX_SCORE_HTTP_RTT_MS)
        val dnsScore = inverseScore(dnsMs, MAX_SCORE_DNS_MS)
        val startupScore = inverseScore(startupMs, MAX_SCORE_STARTUP_MS)
        val jitterScore = latencySamplesMs.jitterScore()
        val packetScore = 1.0 - packetLossRatio
        val blockedScore = ratio(blockedSuccessCount, blockedCheckCount)
        val reconnectScore = ratio(stabilitySuccessCount, stabilityCheckCount)
        val dpiBonus = if (profile.isMobileLike && Server.Tag.MobileBypass in server.tags) MOBILE_BYPASS_TAG_BONUS else 0.0
        return (
            throughputScore * weights.throughput +
                latencyScore * weights.latency +
                dnsScore * weights.dns +
                startupScore * weights.startup +
                jitterScore * weights.jitter +
                packetScore * weights.packetLoss +
                blockedScore * weights.blockedReachability +
                reconnectScore * weights.reconnectStability +
                dpiBonus
            ).coerceIn(0.0, 1.0)
    }

    private fun AutoTunnelProbeResult.toHealthSnapshot(server: Server): HealthSnapshot = HealthSnapshot(
        serverId = server.id,
        timestamp = Clock.System.now(),
        tcpHandshakeMs = startupMs,
        tlsHandshakeMs = null,
        httpRttMs = httpRttMs,
        success = successful,
        networkType = HealthSnapshot.NetworkType.VpnInterface,
    )

    private fun AutoNetworkProfile.weights(): OptimizerWeights = when (networkClass) {
        AutoNetworkClass.Mobile,
        AutoNetworkClass.Metered -> OptimizerWeights(
            throughput = 0.12,
            latency = 0.08,
            dns = 0.06,
            startup = 0.10,
            jitter = 0.08,
            packetLoss = 0.14,
            blockedReachability = 0.30,
            reconnectStability = 0.12,
        )
        AutoNetworkClass.Wifi,
        AutoNetworkClass.Ethernet -> OptimizerWeights(
            throughput = 0.34,
            latency = 0.20,
            dns = 0.08,
            startup = 0.08,
            jitter = 0.10,
            packetLoss = 0.08,
            blockedReachability = 0.06,
            reconnectStability = 0.06,
        )
        AutoNetworkClass.Unknown -> OptimizerWeights(
            throughput = 0.24,
            latency = 0.14,
            dns = 0.08,
            startup = 0.10,
            jitter = 0.10,
            packetLoss = 0.12,
            blockedReachability = 0.14,
            reconnectStability = 0.08,
        )
    }

    private fun ScoredTunnelCandidate.diagnosticLabel(): String =
        "${server.displayName}/tag=$outboundTag/score=${(score * 100).roundToInt()}/${probe.metricsLabel()}"

    private fun AutoTunnelProbeResult.metricsLabel(): String {
        val speedKbps = (throughputBytesPerSecond ?: 0L) * 8L / 1_000L
        val packetLoss = (packetLossRatio * 100.0).roundToInt()
        return "http=${httpRttMs}ms/dns=${dnsMs}ms/speed=${speedKbps}kbps/loss=${packetLoss}%/blocked=$blockedSuccessCount/$blockedCheckCount/stability=$stabilitySuccessCount/$stabilityCheckCount"
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

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private data class OptimizationCandidate(val server: Server, val outboundTag: String)

    private data class ScoredTunnelCandidate(
        val server: Server,
        val outboundTag: String,
        val score: Double,
        val probe: AutoTunnelProbeResult,
    )

    private data class OptimizerWeights(
        val throughput: Double,
        val latency: Double,
        val dns: Double,
        val startup: Double,
        val jitter: Double,
        val packetLoss: Double,
        val blockedReachability: Double,
        val reconnectStability: Double,
    )

    private companion object {
        const val AUTO_SELECTOR_TAG = "auto"
        const val AUTO_OPTIMIZER_SELECTOR_TAG = "auto-optimizer"
        const val CONNECTED_WAIT_TIMEOUT_MS = 20_000L
        const val POST_CONNECT_WARMUP_MS = 2_000L
        const val CANDIDATE_WARMUP_MS = 700L
        // Each in-tunnel probe now downloads ~2 MiB to capture real bandwidth, so we give it
        // more headroom than the previous 12 s budget allowed.
        const val CANDIDATE_PROBE_TIMEOUT_MS = 18_000L
        const val OPTIMIZER_CANDIDATE_LIMIT = 12
        const val SPREAD_CANDIDATE_COUNT = 5
        // 50 Mbps is a more realistic upper bound for a fast residential VPN; the old 3 Mbps cap
        // saturated the throughput component for almost every server, which silently neutralised
        // its weight in the optimiser score.
        const val MAX_SCORE_BYTES_PER_SECOND = 6_250_000L
        const val MAX_SCORE_HTTP_RTT_MS = 2_500
        const val MAX_SCORE_DNS_MS = 1_500
        const val MAX_SCORE_STARTUP_MS = 6_000
        const val MAX_SCORE_JITTER_MS = 800
        const val MOBILE_BYPASS_TAG_BONUS = 0.05
        val STABLE_BOOTSTRAP_COUNTRIES = setOf("EE", "FI", "NL", "DE", "LV", "LT", "PL")
    }
}

private object AutoTunnelProbe {
    suspend fun probe(server: Server, startupMs: Int): AutoTunnelProbeResult {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dns = measureStatusUrl(DNS_PROBE_URL)
            val httpSamples = mutableListOf<Int>()
            var internetChecks = 0
            var internetSuccess = 0

            repeat(HTTP_RTT_SAMPLE_COUNT) {
                internetChecks += 1
                val sample = measureStatusUrl(CONNECTIVITY_URL)
                if (sample.success) {
                    internetSuccess += 1
                    httpSamples += sample.elapsedMs
                }
            }

            if (internetSuccess == 0 && !dns.success) {
                return@withContext AutoTunnelProbeResult(
                    startupMs = null,
                    dnsMs = null,
                    httpRttMs = null,
                    latencySamplesMs = httpSamples,
                    throughputBytesPerSecond = null,
                    internetCheckCount = internetChecks,
                    internetSuccessCount = 0,
                    blockedCheckCount = 0,
                    blockedSuccessCount = 0,
                    stabilityCheckCount = 0,
                    stabilitySuccessCount = 0,
                ).also {
                    Timber.d(
                        "Auto tunnel probe failed early: server=%s internet=%d/%d dns=false",
                        server.displayName,
                        it.internetSuccessCount,
                        it.internetCheckCount,
                    )
                }
            }

            val blockedChecks = listOf(YOUTUBE_URL, TELEGRAM_URL).map { url ->
                measureStatusUrl(url, acceptRedirects = true)
            }
            val speed = measureDownloadBytesPerSecond(SPEED_URL)
            val stabilityChecks = List(STABILITY_CHECK_COUNT) { measureStatusUrl(CONNECTIVITY_URL) }

            AutoTunnelProbeResult(
                startupMs = startupMs.takeIf { internetSuccess > 0 },
                dnsMs = dns.elapsedMs.takeIf { dns.success },
                httpRttMs = httpSamples.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                latencySamplesMs = httpSamples,
                throughputBytesPerSecond = speed.bytesPerSecond,
                internetCheckCount = internetChecks,
                internetSuccessCount = internetSuccess,
                blockedCheckCount = blockedChecks.size,
                blockedSuccessCount = blockedChecks.count { it.success },
                stabilityCheckCount = stabilityChecks.size,
                stabilitySuccessCount = stabilityChecks.count { it.success },
            ).also {
                Timber.d(
                    "Auto tunnel probe completed: server=%s internet=%d/%d blocked=%d/%d stability=%d/%d",
                    server.displayName,
                    it.internetSuccessCount,
                    it.internetCheckCount,
                    it.blockedSuccessCount,
                    it.blockedCheckCount,
                    it.stabilitySuccessCount,
                    it.stabilityCheckCount,
                )
            }
        }
    }

    private fun measureStatusUrl(url: String, acceptRedirects: Boolean = false): ProbeStep {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val connection = (URL(url).openConnection(optimizerProxy()) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                useCaches = false
            }
            try {
                val code = connection.responseCode
                val success = code in 200..299 || (acceptRedirects && code in 300..399)
                ProbeStep(success = success, elapsedMs = elapsedSince(startedAt))
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            ProbeStep(success = false, elapsedMs = elapsedSince(startedAt))
        }
    }

    private fun measureDownloadBytesPerSecond(url: String): DownloadStep {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val connection = (URL(url).openConnection(optimizerProxy()) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                useCaches = false
            }
            var bytes = 0L
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@runCatching DownloadStep(null)
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (bytes < MAX_DOWNLOAD_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, (MAX_DOWNLOAD_BYTES - bytes).toInt()))
                        if (read <= 0) break
                        bytes += read
                    }
                }
                val elapsedMs = elapsedSince(startedAt).coerceAtLeast(1)
                DownloadStep(bytesPerSecond = (bytes * 1_000L) / elapsedMs)
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            DownloadStep(null)
        }
    }

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private fun optimizerProxy(): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(OPTIMIZER_SOCKS_HOST, OPTIMIZER_SOCKS_PORT))

    private data class ProbeStep(val success: Boolean, val elapsedMs: Int)
    private data class DownloadStep(val bytesPerSecond: Long?)

    private const val USER_AGENT = "LisVPN/AutoOptimizer"
    private const val OPTIMIZER_SOCKS_HOST = "127.0.0.1"
    private const val OPTIMIZER_SOCKS_PORT = 2080
    private const val DNS_PROBE_URL = "https://dns.google/resolve?name=www.youtube.com&type=A"
    private const val CONNECTIVITY_URL = "https://www.gstatic.com/generate_204"
    private const val YOUTUBE_URL = "https://www.youtube.com/generate_204"
    private const val TELEGRAM_URL = "https://telegram.org/"
    // Pull a 2 MiB chunk so the throughput measurement actually reflects steady-state bandwidth
    // and not just slow-start. With the 256 KiB chunk we used previously, a fast and a slow tunnel
    // routinely landed within a couple kbps of each other on cellular, which is exactly the
    // "the auto-mode does not really test download speed" complaint we're trying to fix.
    private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=2097152"
    private const val HTTP_RTT_SAMPLE_COUNT = 2
    private const val STABILITY_CHECK_COUNT = 1
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 7_000
    private const val BUFFER_SIZE = 16 * 1024
    private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L
}

private data class AutoTunnelProbeResult(
    val startupMs: Int?,
    val dnsMs: Int?,
    val httpRttMs: Int?,
    val latencySamplesMs: List<Int>,
    val throughputBytesPerSecond: Long?,
    val internetCheckCount: Int,
    val internetSuccessCount: Int,
    val blockedCheckCount: Int,
    val blockedSuccessCount: Int,
    val stabilityCheckCount: Int,
    val stabilitySuccessCount: Int,
) {
    val successful: Boolean
        get() = internetSuccessCount > 0

    val packetLossRatio: Double
        get() {
            val checks = internetCheckCount + blockedCheckCount + stabilityCheckCount
            if (checks <= 0) return 1.0
            val successes = internetSuccessCount + blockedSuccessCount + stabilitySuccessCount
            return 1.0 - (successes.toDouble() / checks.toDouble()).coerceIn(0.0, 1.0)
        }
}
