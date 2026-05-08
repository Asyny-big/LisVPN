package com.lisvpn.android.vpn.health

import com.lisvpn.android.core.domain.model.Server
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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
            .filter { it.success }
            .map { result ->
                AutoSelectionCandidate(
                    taggedServer = result.taggedServer,
                    fastProbe = result,
                    history = histories[result.taggedServer.server.id],
                )
            }
            .sortedWith(
                compareByDescending<AutoSelectionCandidate> { it.fastScore(profile) }
                    .thenBy { it.fastProbe.latencyMs ?: Int.MAX_VALUE },
            )
            .take(limit.coerceAtLeast(1))
            .toList()

    fun score(
        candidate: AutoSelectionCandidate,
        validation: TunnelValidationResult,
        throughput: ThroughputResult?,
        profile: SmartNetworkProfile,
    ): Double {
        if (!validation.eligible) return DEAD_SCORE
        val speedMbps = throughput?.mbps ?: 0.0
        val latencyMs = validation.averageRttMs ?: candidate.fastProbe.latencyMs ?: DEFAULT_BAD_LATENCY_MS
        val jitterMs = validation.jitterMs ?: DEFAULT_BAD_JITTER_MS
        val packetLossPercent = validation.packetLossApprox.coerceIn(0.0, 1.0) * 100.0
        val history = candidate.history
        val historySuccessRate = history?.successRate ?: NEUTRAL_HISTORY_RATE
        val historyScore = history?.lastScore ?: 0.0

        val serviceBonus =
            (if (validation.telegramReachable) TELEGRAM_BONUS else 0.0) +
                (if (validation.youtubeReachable) YOUTUBE_BONUS else 0.0)
        val stabilityBonus =
            (historySuccessRate * HISTORY_SUCCESS_WEIGHT) +
                (if ((history?.lastSuccessAtMs ?: 0L) > 0L) PREVIOUS_SUCCESS_BONUS else 0.0) +
                historyScore.coerceAtMost(HISTORY_SCORE_CAP)
        val networkBonus = when {
            profile.isMobileLike && Server.Tag.MobileBypass in candidate.server.tags -> MOBILE_BYPASS_BONUS
            !profile.isMobileLike && Server.Tag.FastEdge in candidate.server.tags -> FAST_EDGE_BONUS
            Server.Tag.Primary in candidate.server.tags -> PRIMARY_BONUS
            else -> 0.0
        }

        return (
            speedMbps * SPEED_WEIGHT -
                latencyMs * LATENCY_WEIGHT -
                jitterMs * JITTER_WEIGHT -
                packetLossPercent * PACKET_LOSS_WEIGHT +
                serviceBonus +
                stabilityBonus +
                networkBonus
            )
    }

    fun diagnosticScore(score: Double): Int = (score * 10.0).roundToInt()

    private fun AutoSelectionCandidate.fastScore(profile: SmartNetworkProfile): Double {
        val latency = fastProbe.latencyMs ?: DEFAULT_BAD_LATENCY_MS
        val historyRate = history?.successRate ?: NEUTRAL_HISTORY_RATE
        val historyScore = history?.lastScore ?: 0.0
        val tagBonus = when {
            profile.isMobileLike && Server.Tag.MobileBypass in server.tags -> 180.0
            Server.Tag.Primary in server.tags -> 90.0
            Server.Tag.FastEdge in server.tags -> 55.0
            Server.Tag.Backup in server.tags -> -35.0
            else -> 0.0
        }
        return 2_000.0 -
            latency.coerceAtMost(DEFAULT_BAD_LATENCY_MS).toDouble() +
            historyRate * 260.0 +
            historyScore.coerceAtMost(HISTORY_SCORE_CAP) +
            tagBonus
    }

    private companion object {
        const val SHORTLIST_LIMIT = 8
        const val DEAD_SCORE = -10_000.0
        const val DEFAULT_BAD_LATENCY_MS = 2_500
        const val DEFAULT_BAD_JITTER_MS = 800
        const val NEUTRAL_HISTORY_RATE = 0.5
        const val SPEED_WEIGHT = 4.0
        const val LATENCY_WEIGHT = 0.035
        const val JITTER_WEIGHT = 0.08
        const val PACKET_LOSS_WEIGHT = 20.0
        const val TELEGRAM_BONUS = 18.0
        const val YOUTUBE_BONUS = 18.0
        const val HISTORY_SUCCESS_WEIGHT = 32.0
        const val PREVIOUS_SUCCESS_BONUS = 12.0
        const val HISTORY_SCORE_CAP = 80.0
        const val MOBILE_BYPASS_BONUS = 18.0
        const val FAST_EDGE_BONUS = 8.0
        const val PRIMARY_BONUS = 6.0
    }
}
