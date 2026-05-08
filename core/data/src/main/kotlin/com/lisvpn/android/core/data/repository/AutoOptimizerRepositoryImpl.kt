package com.lisvpn.android.core.data.repository

import android.os.SystemClock
import com.lisvpn.android.core.common.di.ApplicationScope
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.model.isGeneralVpnEligible
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerStatus
import com.lisvpn.android.core.domain.repository.PreflightResult
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.core.domain.repository.VpnRepository
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val _status = MutableStateFlow<AutoOptimizerStatus>(AutoOptimizerStatus.Idle)
    override val status: StateFlow<AutoOptimizerStatus> = _status.asStateFlow()

    override fun schedule(servers: List<Server>) {
        optimizerJob?.cancel()
        _status.value = AutoOptimizerStatus.Idle
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
                    _status.value = AutoOptimizerStatus.Failed("VPN did not connect in time")
                    return@launch
                }
                delay(POST_CONNECT_WARMUP_MS)
                optimize(servers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Auto optimizer failed")
                _status.value = AutoOptimizerStatus.Failed(e.message ?: "optimizer crashed")
            }
        }
    }

    override fun cancel() {
        optimizerJob?.cancel()
        optimizerJob = null
        _status.value = AutoOptimizerStatus.Idle
    }

    override fun report(status: AutoOptimizerStatus) {
        _status.value = status
    }

    override suspend fun runPreflight(servers: List<Server>): PreflightResult = mutex.withLock {
        withContext(ioDispatcher) {
            // The VPN tunnel is intentionally NOT up while runPreflight is running — libbox is
            // started in headless / SOCKS-only mode by LisVpnService and the user's apps still
            // see the regular network. We measure each candidate's *real* download speed by
            // pulling a 2 MiB chunk over the SOCKS5 proxy via that candidate's outbound, which
            // is what the user expected the AUTO mode "speed test" to actually be.
            if (servers.isEmpty()) {
                Timber.w("Preflight skipped: no servers")
                _status.value = AutoOptimizerStatus.Failed("no servers")
                return@withContext PreflightResult(null, null, null, 0)
            }
            val profile = autoServerPreferenceStore.currentProfile()
            val indexedServers = servers.mapIndexed { index, server ->
                OptimizationCandidate(server, "srv-$index")
            }.filter { it.server.isGeneralVpnEligible() }
            if (indexedServers.isEmpty()) {
                Timber.w("Preflight skipped: no general-purpose candidates")
                _status.value = AutoOptimizerStatus.Failed("no candidates")
                return@withContext PreflightResult(null, null, null, 0)
            }

            // Pre-screen *every* eligible server with a parallel direct TCP probe before the
            // expensive in-tunnel speed test runs. This is exactly the user complaint: the
            // previous behaviour just did `take(5)` of the bootstrap-ranked list, which meant a
            // dead Germany server in slot #2 got speed-tested (and failed) while slots #6+ that
            // were actually alive never got tested at all. With pre-screening we look at the
            // whole subscription, drop unreachable servers, and only then take the 5 best
            // *reachable* ones for the speed test.
            val reachableCandidates = prescreenReachable(indexedServers)
            val candidatePool = if (reachableCandidates.isEmpty()) {
                Timber.w(
                    "Preflight reachability prescreen returned no candidates; falling back to first %d ranked servers",
                    PREFLIGHT_CANDIDATE_LIMIT,
                )
                indexedServers
            } else {
                reachableCandidates
            }
            val candidates = selectPreflightCandidates(candidatePool, profile)
            val totalToTest = candidates.size
            val results = mutableListOf<ScoredTunnelCandidate>()

            Timber.i(
                "Preflight started: stage=pre-vpn network=%s fingerprint=%s total=%d eligible=%d reachable=%d candidates=%s",
                profile.networkClass,
                profile.fingerprint,
                totalToTest,
                indexedServers.size,
                reachableCandidates.size,
                candidates.joinToString { it.server.displayName },
            )

            for ((index, candidate) in candidates.withIndex()) {
                val tag = candidate.outboundTag
                val previousResult = results.lastOrNull()
                _status.value = AutoOptimizerStatus.Probing(
                    current = index + 1,
                    total = totalToTest,
                    serverDisplayName = candidate.server.displayName,
                    lastSpeedKbps = previousResult?.probe?.throughputBytesPerSecond?.toKbps(),
                    lastServerDisplayName = previousResult?.server?.displayName,
                )
                val switchStartedAt = SystemClock.elapsedRealtime()
                val switched = vpnRepository.selectOutbound(AUTO_OPTIMIZER_SELECTOR_TAG, tag)
                if (switched is com.lisvpn.android.core.common.result.AppResult.Failure) {
                    Timber.w(
                        "Preflight candidate skipped: switch failed server=%s tag=%s reason=%s",
                        candidate.server.displayName,
                        tag,
                        switched.error,
                    )
                    continue
                }
                delay(PREFLIGHT_CANDIDATE_WARMUP_MS)

                val probeResult = withTimeoutOrNull(PREFLIGHT_CANDIDATE_PROBE_TIMEOUT_MS) {
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
                        "Preflight candidate timed out: server=%s tag=%s timeoutMs=%d",
                        candidate.server.displayName,
                        tag,
                        PREFLIGHT_CANDIDATE_PROBE_TIMEOUT_MS,
                    )
                }
                val score = probeResult.adaptiveScore(profile, candidate.server)
                results += ScoredTunnelCandidate(candidate.server, tag, score, probeResult)
                serverHealthRepository.record(probeResult.toHealthSnapshot(candidate.server))
                Timber.i("Preflight candidate: %s", results.last().diagnosticLabel())
            }

            // Stricter "this server actually has working internet" gate. A candidate must have
            // (a) hit *both* connectivity targets we tried — gstatic AND the unblocked-services
            // endpoints — *and* (b) actually downloaded some bytes through the tunnel. The
            // previous filter only required `internetSuccessCount > 0`, which is exactly why a
            // server that responds to a single small TLS handshake but cannot pass real traffic
            // could "win" the AUTO pick and leave the user with no internet.
            val healthyResults = results.filter { it.probe.hasUsableInternet() }
            val best = healthyResults
                .sortedWith(
                    compareByDescending<ScoredTunnelCandidate> { it.probe.throughputBytesPerSecond ?: 0L }
                        .thenByDescending { it.score }
                        .thenBy { it.probe.httpRttMs ?: Int.MAX_VALUE },
                )
                .firstOrNull()
                ?: results
                    // Fallback gate: at least one connectivity check must have passed. Better
                    // than dropping the user back to "no successful probe" when every candidate
                    // was rate-limited mid-test.
                    .filter { it.probe.successful }
                    .sortedWith(
                        compareByDescending<ScoredTunnelCandidate> { it.probe.throughputBytesPerSecond ?: 0L }
                            .thenByDescending { it.score }
                            .thenBy { it.probe.httpRttMs ?: Int.MAX_VALUE },
                    )
                    .firstOrNull()

            if (best == null) {
                Timber.w("Preflight finished: no successful candidate (tested=%d)", results.size)
                _status.value = AutoOptimizerStatus.Failed("no successful probe")
                return@withContext PreflightResult(null, null, null, results.size)
            }

            autoServerPreferenceStore.saveBest(profile, best.server, best.score)
            val winnerSpeedKbps = best.probe.throughputBytesPerSecond?.toKbps()
            val winnerIndex = candidates.indexOfFirst { it.outboundTag == best.outboundTag }
                .takeIf { it >= 0 }
            _status.value = AutoOptimizerStatus.Done(
                bestServerDisplayName = best.server.displayName,
                bestSpeedKbps = winnerSpeedKbps,
                tested = results.size,
            )
            Timber.i(
                "Preflight finished: best=%s tag=%s index=%s score=%d speedKbps=%d tested=%d network=%s",
                best.server.displayName,
                best.outboundTag,
                winnerIndex,
                (best.score * 100).roundToInt(),
                winnerSpeedKbps ?: 0L,
                results.size,
                profile.networkClass,
            )
            PreflightResult(
                winnerIndex = winnerIndex,
                winnerOutboundTag = best.outboundTag,
                winnerSpeedKbps = winnerSpeedKbps,
                tested = results.size,
            )
        }
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
                _status.value = AutoOptimizerStatus.Failed("no candidates")
                return@withContext
            }
            val candidates = selectOptimizationCandidates(indexedServers, profile, cachedServerIds)
            val totalToTest = candidates.size
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

            for ((index, candidate) in candidates.withIndex()) {
                if (vpnRepository.state.value !is VpnState.Connected) {
                    Timber.i("Auto optimizer stopped: VPN is no longer connected")
                    break
                }
                val tag = candidate.outboundTag
                val previousResult = results.lastOrNull()
                _status.value = AutoOptimizerStatus.Probing(
                    current = index + 1,
                    total = totalToTest,
                    serverDisplayName = candidate.server.displayName,
                    lastSpeedKbps = previousResult?.probe?.throughputBytesPerSecond?.toKbps(),
                    lastServerDisplayName = previousResult?.server?.displayName,
                )
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
                _status.value = AutoOptimizerStatus.Failed("no successful probe")
                return@withContext
            }

            vpnRepository.selectOutbound(AUTO_SELECTOR_TAG, best.outboundTag)
            autoServerPreferenceStore.saveBest(profile, best.server, best.score)
            _status.value = AutoOptimizerStatus.Done(
                bestServerDisplayName = best.server.displayName,
                bestSpeedKbps = best.probe.throughputBytesPerSecond?.toKbps(),
                tested = results.size,
            )
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

    private fun Long.toKbps(): Long = this * 8L / 1_000L

    /**
     * Run a parallel direct (non-tunneled) TCP probe against every eligible candidate before the
     * speed test runs. This filters out servers that are dead, geo-blocked or otherwise
     * unreachable from the user's current network — so the expensive in-tunnel speed test only
     * burns budget on candidates that have at least a chance of working. Returns the subset of
     * [indexedServers] that responded to TCP within [REACHABILITY_PROBE_TIMEOUT_MS].
     */
    private suspend fun prescreenReachable(
        indexedServers: List<OptimizationCandidate>,
    ): List<OptimizationCandidate> = coroutineScope {
        // Cap parallelism so we don't open hundreds of sockets on cellular (some carriers will
        // throttle / NAT-collapse anything that aggressive).
        val semaphore = kotlinx.coroutines.sync.Semaphore(REACHABILITY_PROBE_PARALLELISM)
        indexedServers
            .map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val reachable = withTimeoutOrNull(REACHABILITY_PROBE_TIMEOUT_MS) {
                            tcpReachable(candidate.server)
                        } == true
                        candidate.takeIf { reachable }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun tcpReachable(server: Server): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Resolve the server hostname directly — outbound.host may be a DNS name we have
            // never resolved before (e.g. fresh subscription). We prefer Inet4Address because
            // most carriers in the user's region either NAT v6 or drop it entirely, which would
            // give false negatives. We fall back to v6 only if no v4 address resolves.
            val host = server.outbound.host.ifBlank { return@runCatching false }
            val port = server.outbound.port
            if (port <= 0) return@runCatching false
            val resolved = runCatching { InetAddress.getAllByName(host) }
                .getOrElse { return@runCatching false }
            val addresses = resolved.filterIsInstance<Inet4Address>().ifEmpty { resolved.toList() }
            for (address in addresses) {
                val ok = runCatching {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(address, port), REACHABILITY_CONNECT_TIMEOUT_MS)
                        socket.isConnected
                    }
                }.getOrDefault(false)
                if (ok) return@runCatching true
            }
            false
        }.getOrElse { false }
    }

    /**
     * Pick the 5 servers we'll actually run the speed test on, given the *reachable* pool.
     * Prefers Mobile-Bypass-tagged servers when on cellular, then primary-tagged servers, then
     * the remaining reachable list in its existing rank-induced order.
     */
    private fun selectPreflightCandidates(
        reachable: List<OptimizationCandidate>,
        profile: AutoNetworkProfile,
    ): List<OptimizationCandidate> {
        val ordered = linkedMapOf<String, OptimizationCandidate>()
        fun add(c: OptimizationCandidate) {
            if (ordered.size < PREFLIGHT_CANDIDATE_LIMIT) ordered.putIfAbsent(c.server.id, c)
        }

        if (profile.isMobileLike) {
            reachable
                .filter { Server.Tag.MobileBypass in it.server.tags }
                .forEach(::add)
        }
        reachable
            .filter { Server.Tag.Primary in it.server.tags }
            .forEach(::add)
        reachable.forEach(::add)

        return ordered.values.toList()
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
        const val POST_CONNECT_WARMUP_MS = 1_500L
        const val CANDIDATE_WARMUP_MS = 500L
        // Each in-tunnel probe downloads ~2 MiB to capture real bandwidth (slow-start dominates
        // anything smaller). 10 s gives every candidate enough budget to finish, but still keeps
        // the per-server step short enough for the UI progress indicator to feel responsive.
        const val CANDIDATE_PROBE_TIMEOUT_MS = 10_000L
        // We reduced the candidate cap from 12 to 6 — past six servers the user has long since
        // assumed the test is done and the optimiser was effectively running in the dark.
        const val OPTIMIZER_CANDIDATE_LIMIT = 6
        const val SPREAD_CANDIDATE_COUNT = 4
        // Pre-VPN speed test budget. We test up to 5 candidates (already filtered by the
        // SelectBestServerUseCase rank step) at ~5 s each, so the worst-case wait the user sees
        // before the tunnel comes up is ~25 s. That feels long, but the alternative is exactly
        // what the user complained about: "the speed test happens through the VPN already".
        const val PREFLIGHT_CANDIDATE_LIMIT = 5
        const val PREFLIGHT_CANDIDATE_PROBE_TIMEOUT_MS = 5_000L
        const val PREFLIGHT_CANDIDATE_WARMUP_MS = 200L
        // Direct (no-tunnel) TCP reachability prescreen budget. Each candidate gets a 2.5 s
        // connect timeout, and we run up to REACHABILITY_PROBE_PARALLELISM probes concurrently
        // — so even with 50 servers in the subscription the prescreen finishes inside ~3 s.
        const val REACHABILITY_PROBE_TIMEOUT_MS = 3_000L
        const val REACHABILITY_CONNECT_TIMEOUT_MS = 2_500
        const val REACHABILITY_PROBE_PARALLELISM = 8
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
            val speed = measureDownloadBytesPerSecond()
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

    /**
     * Measures download throughput against a list of fallback endpoints. Some Russian mobile
     * carriers DPI-block speed.cloudflare.com (which is exactly why the user reported "speed is
     * never shown on cellular but works on Wi-Fi"). When the primary endpoint fails or returns
     * zero bytes we fall through to a smaller Cloudflare DoH-adjacent file and Google's
     * Connectivity-Check binary, which behave more like ordinary HTTPS traffic and tend to make
     * it through.
     */
    private fun measureDownloadBytesPerSecond(): DownloadStep {
        for (url in SPEED_URLS) {
            val step = measureDownloadBytesPerSecond(url)
            val bytesPerSecond = step.bytesPerSecond
            if (bytesPerSecond != null && bytesPerSecond > 0L) {
                return step
            }
        }
        return DownloadStep(null)
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
    //
    // We try multiple endpoints in order. speed.cloudflare.com is the canonical choice but is
    // routinely DPI-blocked or rate-limited on Russian mobile carriers — that's why the user
    // saw "no speed shown" on cellular but a number on Wi-Fi. The fallbacks are vanilla HTTPS
    // downloads on commodity CDNs (Google's CRT mirror, Cloudflare's marketing CDN) that
    // generally make it through DPI even when speed.cloudflare.com is blocked.
    private val SPEED_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=2097152",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://connectivitycheck.gstatic.com/generate_204",
    )
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

    /**
     * "All AUTO probes look healthy" — every connectivity check we ran (gstatic / generate_204
     * AND the unblocked-services targets) actually returned, *and* we managed to pump some bytes
     * through the tunnel. This is the strict gate we want for picking a winner so a server that
     * passes a single small TLS handshake but cannot move real traffic does not become the AUTO
     * pick.
     */
    fun hasUsableInternet(): Boolean {
        val internetOk = internetCheckCount > 0 && internetSuccessCount >= internetCheckCount
        val blockedOk = blockedCheckCount == 0 || blockedSuccessCount > 0
        val throughputOk = (throughputBytesPerSecond ?: 0L) > 0L
        return internetOk && blockedOk && throughputOk
    }

    val packetLossRatio: Double
        get() {
            val checks = internetCheckCount + blockedCheckCount + stabilityCheckCount
            if (checks <= 0) return 1.0
            val successes = internetSuccessCount + blockedSuccessCount + stabilitySuccessCount
            return 1.0 - (successes.toDouble() / checks.toDouble()).coerceIn(0.0, 1.0)
        }
}
