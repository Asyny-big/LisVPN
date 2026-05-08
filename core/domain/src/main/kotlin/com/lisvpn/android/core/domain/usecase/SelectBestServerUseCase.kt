package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * App-side AUTO bootstrap ordering.
 *
 * Two-stage selection:
 *   1. Heuristic rank by historical health scores, network profile, and stable bootstrap tags.
 *   2. Real pre-connect probe (parallel TCP/TLS) of the top [BOOTSTRAP_PROBE_FANOUT] candidates,
 *      bounded by [BOOTSTRAP_PROBE_BUDGET_MS]. Whoever answers fastest moves to the front of the
 *      list, so the user does not get stuck dialing a dead Estonia node just because heuristics
 *      love it. The post-connect AutoOptimizer is what later swaps to the actual fastest tunnel
 *      (with throughput measurement); this stage just makes sure the bootstrap connect itself
 *      lands on a server that is alive on the current network.
 */
class SelectBestServerUseCase @Inject constructor(
    private val healthRepository: ServerHealthRepository,
) {
    suspend operator fun invoke(servers: List<Server>, limit: Int): List<Server> {
        if (servers.isEmpty()) return emptyList()
        val ranked = healthRepository.rank(servers, limit)
        if (ranked.isEmpty()) {
            Timber.i("Auto server selection result: requested=%d ranked=0", servers.size)
            return ranked
        }
        val refined = refineWithLiveProbe(ranked)
        Timber.i(
            "Auto server selection result: requested=%d ranked=%d refined=%s",
            servers.size,
            refined.size,
            refined.joinToString { it.displayName },
        )
        return refined
    }

    private suspend fun refineWithLiveProbe(ranked: List<Server>): List<Server> {
        val probeTargets = ranked.take(BOOTSTRAP_PROBE_FANOUT)
        if (probeTargets.size <= 1) return ranked

        val probeOutcomes = withTimeoutOrNull(BOOTSTRAP_PROBE_BUDGET_MS) {
            coroutineScope {
                probeTargets.map { server ->
                    server to async<HealthSnapshot?> {
                        when (val result = healthRepository.probe(server)) {
                            is AppResult.Success -> result.value
                            is AppResult.Failure -> null
                        }
                    }
                }.awaitAllSafely()
            }
        }.orEmpty()

        if (probeOutcomes.isEmpty()) {
            Timber.i("Auto bootstrap live probe skipped: budget exhausted with no samples")
            return ranked
        }

        val priorities = probeOutcomes.associate { (server, snap) -> server.id to snap.bootstrapPriority() }
        val refined = ranked.sortedWith(
            compareByDescending<Server> { priorities[it.id] ?: Double.NEGATIVE_INFINITY }
                .thenBy { ranked.indexOf(it) },
        )

        Timber.i(
            "Auto bootstrap live probe: probed=%d front=%s details=%s",
            probeOutcomes.size,
            refined.firstOrNull()?.displayName,
            probeOutcomes.joinToString { (server, snap) ->
                val rtt = snap?.tcpHandshakeMs ?: snap?.httpRttMs
                "${server.displayName}=${if (snap?.success == true) "ok/${rtt}ms" else "fail"}"
            },
        )
        return refined
    }

    private fun HealthSnapshot?.bootstrapPriority(): Double {
        if (this == null) return -1_000.0
        if (!success) return -500.0
        val rtt = tcpHandshakeMs ?: httpRttMs ?: tlsHandshakeMs ?: 1_500
        // Inverse latency: fastest ping ranks highest. 0ms => 1500 score, 1500ms => 0 score.
        return (1_500 - rtt.coerceIn(0, 1_500)).toDouble()
    }

    private suspend fun <K, V> List<Pair<K, Deferred<V>>>.awaitAllSafely(): List<Pair<K, V>> =
        map { (key, deferred) -> key to deferred.await() }

    private companion object {
        const val BOOTSTRAP_PROBE_FANOUT = 5
        const val BOOTSTRAP_PROBE_BUDGET_MS = 4_500L
    }
}
