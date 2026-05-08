package com.lisvpn.android.vpn.health

import com.lisvpn.android.core.domain.model.Server
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

interface AutoFailoverSwitchController {
    suspend fun switchTo(outboundTag: String): Boolean
    suspend fun onFailoverStarted(attempt: Int, fromServerDisplayName: String?)
    suspend fun onFailoverSucceeded(server: Server, outboundTag: String)
    suspend fun onFailoverExhausted(reason: String)
}

@Singleton
class AutoFailoverManager @Inject constructor(
    private val healthMonitor: HealthMonitor,
    private val tunnelValidationWorker: TunnelValidationWorker,
    private val throughputWorker: ThroughputWorker,
    private val scoreCalculator: ScoreCalculator,
    private val smartServerCache: SmartServerCache,
) {
    private val mutex = Mutex()

    fun start(
        scope: CoroutineScope,
        profile: SmartNetworkProfile,
        validatedServers: List<ScoredAutoServer>,
        initialOutboundTag: String,
        switchController: AutoFailoverSwitchController,
    ): Job {
        if (validatedServers.size <= 1) {
            return scope.launch { Timber.i("Auto failover skipped: only one validated server") }
        }
        val ranked = validatedServers
            .filter { it.eligible }
            .sortedWith(
                compareByDescending<ScoredAutoServer> { it.score }
                    .thenByDescending { it.throughput?.mbps ?: 0.0 },
            )
        return scope.launch {
            var currentTag = initialOutboundTag
            var failedChecks = 0
            var attempt = 0
            healthMonitor.monitor().collect { health ->
                if (!isActive) return@collect
                if (health.healthy) {
                    failedChecks = 0
                    return@collect
                }
                failedChecks += 1
                Timber.w(
                    "Tunnel health check failed: failures=%d elapsed=%s reason=%s",
                    failedChecks,
                    health.elapsedMs,
                    health.reason,
                )
                if (failedChecks < FAILOVER_FAILURE_THRESHOLD) return@collect
                attempt += 1
                val switched = failover(
                    profile = profile,
                    ranked = ranked,
                    currentTag = currentTag,
                    attempt = attempt,
                    switchController = switchController,
                )
                if (switched != null) {
                    currentTag = switched
                }
                failedChecks = 0
                delay(FAILOVER_BACKOFF_MS)
            }
        }
    }

    private suspend fun failover(
        profile: SmartNetworkProfile,
        ranked: List<ScoredAutoServer>,
        currentTag: String,
        attempt: Int,
        switchController: AutoFailoverSwitchController,
    ): String? = mutex.withLock {
        val currentServerName = ranked.firstOrNull { it.outboundTag == currentTag }?.server?.displayName
        val nextCandidates = ranked.filter { it.outboundTag != currentTag }
        if (nextCandidates.isEmpty()) {
            switchController.onFailoverExhausted("no standby validated servers")
            return@withLock null
        }

        switchController.onFailoverStarted(attempt, currentServerName)
        for (candidate in nextCandidates) {
            Timber.w(
                "Auto failover candidate: server=%s tag=%s attempt=%d",
                candidate.server.displayName,
                candidate.outboundTag,
                attempt,
            )
            if (!switchController.switchTo(candidate.outboundTag)) continue
            delay(ROUTE_PROPAGATION_MS)
            val validation = tunnelValidationWorker.validate(candidate.server.id)
            val throughput = if (validation.eligible) throughputWorker.measure() else null
            val validationForScore = if (validation.eligible && throughput?.success != true) {
                validation.copy(
                    eligible = false,
                    failureReason = throughput?.error ?: "throughput validation failed",
                )
            } else {
                validation
            }
            val score = scoreCalculator.score(candidate.candidate, validationForScore, throughput, profile)
            val refreshed = candidate.copy(validation = validationForScore, throughput = throughput, score = score)
            smartServerCache.record(profile, refreshed)
            if (validationForScore.eligible) {
                Timber.i(
                    "Auto failover succeeded: server=%s tag=%s score=%d",
                    candidate.server.displayName,
                    candidate.outboundTag,
                    scoreCalculator.diagnosticScore(score),
                )
                switchController.onFailoverSucceeded(candidate.server, candidate.outboundTag)
                return@withLock candidate.outboundTag
            }
            Timber.w(
                "Auto failover candidate dead after switch: server=%s reason=%s",
                candidate.server.displayName,
                validationForScore.failureReason,
            )
        }
        switchController.switchTo(currentTag)
        switchController.onFailoverExhausted("all standby servers failed validation")
        null
    }

    private companion object {
        const val FAILOVER_FAILURE_THRESHOLD = 2
        const val FAILOVER_BACKOFF_MS = 15_000L
        const val ROUTE_PROPAGATION_MS = 1_200L
    }
}
