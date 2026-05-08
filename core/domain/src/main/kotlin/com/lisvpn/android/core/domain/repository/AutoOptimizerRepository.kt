package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.domain.model.Server
import kotlinx.coroutines.flow.StateFlow

interface AutoOptimizerRepository {
    fun schedule(servers: List<Server>)
    fun cancel()
    val status: StateFlow<AutoOptimizerStatus>
}

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
