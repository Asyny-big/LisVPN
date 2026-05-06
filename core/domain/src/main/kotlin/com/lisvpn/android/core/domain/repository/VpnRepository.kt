package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import kotlinx.coroutines.flow.StateFlow

/**
 * Façade over the VpnService + libbox runtime. Implementation lives in `:core:data` and delegates
 * to `:vpn:core` controller. UI layer must NOT touch the controller directly.
 */
interface VpnRepository {

    /** Hot stream of current state. Single source of truth for the UI. */
    val state: StateFlow<VpnState>

    /**
     * Start the tunnel using the given subset of servers. The implementation is responsible for:
     *  - Building a sing-box JSON config (with `urltest` if [smartSelection]).
     *  - Asking for VpnService.prepare() permission via the [VpnPermissionHandle].
     *  - Starting [com.lisvpn.android.vpn.core.LisVpnService] as foreground.
     *
     * Returns [AppResult.Success] once the start command has been dispatched (NOT once
     * connection is established) — observe [state] to react to the tunnel coming up.
     */
    suspend fun start(
        servers: List<Server>,
        smartSelection: Boolean,
        permission: VpnPermissionHandle,
    ): AppResult<Unit>

    /** Graceful teardown. */
    suspend fun stop(): AppResult<Unit>

    /**
     * Hard reset of state machine after a fatal error. Does NOT contact libbox.
     */
    fun acknowledgeError()
}

/**
 * Bridge that hands user-visible permission flow back to the platform. Activity/feature module
 * supplies an implementation that wraps `ActivityResultLauncher<Intent>`.
 */
interface VpnPermissionHandle {
    /** @return true if granted (or already granted). */
    suspend fun ensureGranted(): Boolean
}
