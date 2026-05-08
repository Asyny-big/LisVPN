package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.domain.model.Server
import kotlinx.coroutines.flow.StateFlow

interface AutoOptimizerRepository {
    /**
     * Schedules the post-connect, in-tunnel optimizer. Kept on the interface for backwards
     * compatibility — current AUTO mode flow runs [runPreflight] before connecting and does
     * NOT call this anymore (the user explicitly asked for speed testing to happen *before*
     * the tunnel comes up, so re-testing inside the tunnel would only confuse them).
     */
    fun schedule(servers: List<Server>)

    fun cancel()

    val status: StateFlow<AutoOptimizerStatus>

    /**
     * Runs the AUTO mode pre-VPN speed test against [servers] and returns the index (in the
     * supplied list) of the fastest candidate, or `null` if every candidate failed.
     *
     * Preconditions:
     *  - libbox is already running with a *headless* config (no TUN, only the SOCKS5 mixed
     *    inbound on 127.0.0.1:[com.lisvpn.android.vpn.config.SingBoxConfigBuilder.OPTIMIZER_SOCKS_PORT]).
     *  - The selector tag `auto-optimizer` is wired to the same outbound tags the speed-test loop
     *    expects (`srv-0` … `srv-N`).
     *
     * The implementation publishes [AutoOptimizerStatus.Probing] / [AutoOptimizerStatus.Done] /
     * [AutoOptimizerStatus.Failed] on [status] just like the in-tunnel optimizer, so the Home
     * screen progress indicator works for both phases.
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
 * Real-time progress of the in-tunnel auto-optimizer.
 *
 * The optimizer iterates candidate servers serially (it has to switch the active outbound
 * before each measurement), so this flow exists to surface that progress to the UI. Without
 * it, the user sees an instantaneous "Connected" state right after the bootstrap pick and
 * has no way to know that the real download-speed test is running in the background — the
 * exact confusion the user reported.
 */
sealed interface AutoOptimizerStatus {
    data object Idle : AutoOptimizerStatus

    data class Probing(
        val current: Int,
        val total: Int,
        val serverDisplayName: String,
        val lastSpeedKbps: Long? = null,
        val lastServerDisplayName: String? = null,
    ) : AutoOptimizerStatus

    data class Done(
        val bestServerDisplayName: String,
        val bestSpeedKbps: Long?,
        val tested: Int,
    ) : AutoOptimizerStatus

    data class Failed(val reason: String) : AutoOptimizerStatus
}
