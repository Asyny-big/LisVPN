package com.lisvpn.android.vpn.health

import com.lisvpn.android.core.domain.model.Server
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AUTO selection scoring.
 *
 * The previous formula gave throughput a flat weight of 4 per Mbps, which made a 5 Mbps server
 * worth +20 — easily beaten by the combined service / history / tag bonuses that could add up to
 * ~150 even for a slower or stale-cached server. On mobile / whitelist networks where the speed
 * sample regularly failed (Throughput=0) this meant the winner was effectively picked by the
 * service bonus alone, not by actual measured speed. That is the "AUTO mode does not pick the
 * fastest server" complaint.
 *
 * The current formula:
 *   - dominates by measured throughput using a square-root curve so 1 -> 4 -> 16 -> 64 Mbps each
 *     give meaningful but diminishing improvements (30 / 60 / 120 / 240 points);
 *   - keeps latency / jitter / packet loss as second-order signal;
 *   - reduces history / service / tag effects to small ± tie-breakers so a fresh measurement
 *     always trumps stale cache and a single missing service does not flip a winner;
 *   - penalizes high jitter and packet loss harder on mobile-like profiles where DPI throttling
 *     manifests as those exact signals.
 */
@Singleton
class ScoreCalculator @Inject constructor() {

    fun shortlist(
        fastResults: List<FastProbeResult>,
        histories: Map<String, SmartServerHistory>,
        profile: SmartNetworkProfile,
        limit: Int = SHORTLIST_LIMIT,
    ): List<AutoSelectionCandidate> =
        fastResults
            .asSequence()
            .toAutoSelectionCandidates(histories)
            .rankByFastScore(profile)
            .take(limit.coerceAtLeast(1))
            .toList()

    fun rankFastResults(
        fastResults: List<FastProbeResult>,
        histories: Map<String, SmartServerHistory>,
        profile: SmartNetworkProfile,
    ): List<AutoSelectionCandidate> =
        fastResults
            .asSequence()
            .filter { it.success }
            .toAutoSelectionCandidates(histories)
            .rankByFastScore(profile)
            .toList()

    private fun Sequence<FastProbeResult>.toAutoSelectionCandidates(
        histories: Map<String, SmartServerHistory>,
    ): Sequence<AutoSelectionCandidate> =
        map { result ->
            AutoSelectionCandidate(
                taggedServer = result.taggedServer,
                fastProbe = result,
                history = histories[result.taggedServer.server.id],
            )
        }

    private fun Sequence<AutoSelectionCandidate>.rankByFastScore(
        profile: SmartNetworkProfile,
    ): Sequence<AutoSelectionCandidate> =
        sortedWith(
            compareByDescending<AutoSelectionCandidate> { it.fastScore(profile) }
                .thenBy { it.fastProbe.latencyMs ?: Int.MAX_VALUE },
        )

    fun score(
        candidate: AutoSelectionCandidate,
        validation: TunnelValidationResult,
        throughput: ThroughputResult?,
        profile: SmartNetworkProfile,
    ): Double {
        if (!validation.eligible) return DEAD_SCORE
        val speedMbps = (throughput?.mbps ?: 0.0).coerceAtLeast(0.0)
        val latencyMs = validation.averageRttMs ?: candidate.fastProbe.latencyMs ?: DEFAULT_BAD_LATENCY_MS
        val jitterMs = validation.jitterMs ?: DEFAULT_BAD_JITTER_MS
        val packetLossPercent = validation.packetLossApprox.coerceIn(0.0, 1.0) * 100.0
        val history = candidate.history
        val historySuccessRate = history?.successRate ?: NEUTRAL_HISTORY_RATE
        val historyScore = history?.lastScore ?: 0.0

        // Speed is the dominant signal. We use sqrt so a single fast outlier dominates the rest
        // while differences in the high band (50 vs 100 Mbps) still register without being
        // ten times the contribution of the low band (1 vs 5 Mbps).
        val speedScore = sqrt(speedMbps) * SPEED_WEIGHT
        // Log curve on latency so a 50ms server is "much better" than a 200ms server but a 500ms
        // and 1000ms server are similar (both bad).
        val latencyPenalty = ln(latencyMs.coerceAtLeast(1).toDouble() + 1.0) * LATENCY_WEIGHT
        val jitterScale = if (profile.isMobileLike) JITTER_WEIGHT_MOBILE else JITTER_WEIGHT_FIXED
        val lossScale = if (profile.isMobileLike) PACKET_LOSS_WEIGHT_MOBILE else PACKET_LOSS_WEIGHT_FIXED
        val jitterPenalty = jitterMs * jitterScale
        val packetLossPenalty = packetLossPercent * lossScale

        val serviceBonus =
            (if (validation.telegramReachable) TELEGRAM_BONUS else 0.0) +
                (if (validation.youtubeReachable) YOUTUBE_BONUS else 0.0)
        val stabilityBonus =
            (historySuccessRate * HISTORY_SUCCESS_WEIGHT) +
                (if ((history?.lastSuccessAtMs ?: 0L) > 0L) PREVIOUS_SUCCESS_BONUS else 0.0) +
                historyScore.coerceIn(-HISTORY_SCORE_CAP, HISTORY_SCORE_CAP) * HISTORY_SCORE_WEIGHT
        val networkBonus = when {
            profile.isMobileLike && Server.Tag.MobileBypass in candidate.server.tags -> MOBILE_BYPASS_BONUS
            !profile.isMobileLike && Server.Tag.FastEdge in candidate.server.tags -> FAST_EDGE_BONUS
            Server.Tag.Primary in candidate.server.tags -> PRIMARY_BONUS
            else -> 0.0
        }

        return speedScore -
            latencyPenalty -
            jitterPenalty -
            packetLossPenalty +
            serviceBonus +
            stabilityBonus +
            networkBonus
    }

    fun diagnosticScore(score: Double): Int = (score * 10.0).roundToInt()

    private fun AutoSelectionCandidate.fastScore(profile: SmartNetworkProfile): Double {
        val latency = fastProbe.latencyMs ?: DEFAULT_BAD_LATENCY_MS
        val historyRate = history?.successRate ?: NEUTRAL_HISTORY_RATE
        val historySpeedKbps = history?.lastThroughputKbps ?: 0L
        // We use cached throughput as a strong prior for the shortlist so servers that recently
        // measured fast on the same network class bubble back to the top and survive a transient
        // probe failure. This is what re-uses Stage-3 results to make Stage-1 smarter.
        val historySpeedBonus = sqrt(max(historySpeedKbps, 0L) / 1_000.0) * FAST_PROBE_HISTORY_SPEED_WEIGHT
        val historyScore = (history?.lastScore ?: 0.0).coerceIn(-HISTORY_SCORE_CAP, HISTORY_SCORE_CAP)
        val tagBonus = when {
            // Tag biases are kept proportional to a typical fast-score range (~2000) so they shift
            // a candidate forward/backward by ~5-10 positions but never override a clearly faster
            // server. The old +180 for MobileBypass made mobile selection rely entirely on the tag.
            profile.isMobileLike && Server.Tag.MobileBypass in server.tags -> 80.0
            Server.Tag.Primary in server.tags -> 40.0
            Server.Tag.FastEdge in server.tags -> 25.0
            Server.Tag.Backup in server.tags -> -20.0
            else -> 0.0
        }
        return 2_000.0 -
            latency.coerceAtMost(DEFAULT_BAD_LATENCY_MS).toDouble() +
            historyRate * FAST_PROBE_HISTORY_RATE_WEIGHT +
            historySpeedBonus +
            historyScore * FAST_PROBE_HISTORY_SCORE_WEIGHT +
            tagBonus
    }

    private companion object {
        const val SHORTLIST_LIMIT = 8
        const val DEAD_SCORE = -10_000.0
        const val DEFAULT_BAD_LATENCY_MS = 2_500
        const val DEFAULT_BAD_JITTER_MS = 800
        const val NEUTRAL_HISTORY_RATE = 0.5

        // Speed contribution: sqrt(mbps) * 30
        //   1 Mbps  -> 30
        //   4 Mbps  -> 60
        //  16 Mbps  -> 120
        //  64 Mbps  -> 240
        // 128 Mbps  -> ~339
        const val SPEED_WEIGHT = 30.0

        // Latency penalty: ln(rtt+1) * 6
        //   50 ms  -> ~23
        //  200 ms  -> ~32
        //  500 ms  -> ~37
        // 1000 ms  -> ~41
        const val LATENCY_WEIGHT = 6.0

        // Per-ms jitter penalty. On mobile DPI we punish jitter harder because that is the typical
        // signature of throttled / shaped tunnels.
        const val JITTER_WEIGHT_FIXED = 0.04
        const val JITTER_WEIGHT_MOBILE = 0.08

        // Per-percent packet loss penalty. Aggressive on mobile for the same reason as jitter.
        const val PACKET_LOSS_WEIGHT_FIXED = 2.5
        const val PACKET_LOSS_WEIGHT_MOBILE = 5.0

        // Service bonuses are tie-breakers. A working telegram path or youtube path adds a small
        // amount but never enough to flip an obviously faster server.
        const val TELEGRAM_BONUS = 5.0
        const val YOUTUBE_BONUS = 5.0

        // History contribution: caps and weights are intentionally small so that a fresh
        // measurement always trumps a stale cache. Cache is a hint, not a verdict.
        const val HISTORY_SUCCESS_WEIGHT = 8.0
        const val PREVIOUS_SUCCESS_BONUS = 3.0
        const val HISTORY_SCORE_CAP = 80.0
        const val HISTORY_SCORE_WEIGHT = 0.05

        // Network-class tag bonus, capped low. Tags act as a prior, not as a winner-picker.
        const val MOBILE_BYPASS_BONUS = 8.0
        const val FAST_EDGE_BONUS = 4.0
        const val PRIMARY_BONUS = 3.0

        // Fast-probe (Stage 1) weights — these only affect the shortlist, not the final winner.
        const val FAST_PROBE_HISTORY_RATE_WEIGHT = 260.0
        const val FAST_PROBE_HISTORY_SCORE_WEIGHT = 1.5
        const val FAST_PROBE_HISTORY_SPEED_WEIGHT = 250.0
    }
}
