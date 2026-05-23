package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.domain.model.Server
import kotlinx.coroutines.flow.StateFlow

interface AutoOptimizerRepository {
    /**
     * Legacy selector optimizer kept for backwards compatibility. Production AUTO now runs from
     * VpnService as a guarded state machine: fast filter -> real TUN validation -> mini speed test.
     */
    fun schedule(servers: List<Server>)

    fun cancel()

    val status: StateFlow<AutoOptimizerStatus>

    /**
     * Publishes progress from the production AUTO pipeline that lives in VpnService. Keeping this
     * here lets the Home screen keep observing a single status stream while selection moved from the
     * old SOCKS preflight into real in-tunnel validation.
     */
    fun report(status: AutoOptimizerStatus)

    /**
     * Legacy SOCKS-only preflight. New AUTO mode does not call this because it must validate real
     * internet through Android's VPN network before a server is marked eligible.
     */
    suspend fun runPreflight(servers: List<Server>): PreflightResult
}

data class PreflightResult(
    val winnerIndex: Int?,
    val winnerOutboundTag: String?,
    val winnerSpeedKbps: Long?,
    val tested: Int,
)

/**
 * Real-time progress of AUTO selection.
 *
 * The production pipeline is intentionally stage-aware so UI, logs and support dumps can explain
 * what is happening: cheap direct probes, real tunnel validation, mini speed test, winner switch.
 * Extra fields have defaults to keep the legacy optimizer source-compatible.
 */
sealed interface AutoOptimizerStatus {
    data object Idle : AutoOptimizerStatus

    data class Probing(
        val current: Int,
        val total: Int,
        val serverDisplayName: String,
        val lastSpeedKbps: Long? = null,
        val lastServerDisplayName: String? = null,
        val stage: AutoOptimizerStage = AutoOptimizerStage.FastFilter,
        val stageMessage: String? = null,
        val progressPercent: Int? = null,
        val estimatedRemainingMs: Long? = null,
        val checked: Int? = null,
        val reachable: Int? = null,
        val debugSummary: String? = null,
    ) : AutoOptimizerStatus

    data class Done(
        val bestServerDisplayName: String,
        val bestSpeedKbps: Long?,
        val tested: Int,
        val total: Int? = null,
        val elapsedMs: Long? = null,
        val selectionReason: String? = null,
        val debugSummary: String? = null,
    ) : AutoOptimizerStatus

    data class Failed(
        val reason: String,
        val stage: AutoOptimizerStage? = null,
        val tested: Int? = null,
        val total: Int? = null,
        val debugSummary: String? = null,
    ) : AutoOptimizerStatus
}

enum class AutoOptimizerStage {
    BootstrapRanking,
    FastFilter,
    TunnelValidation,
    SpeedTest,
    SelectingWinner,
    Failover,
}
