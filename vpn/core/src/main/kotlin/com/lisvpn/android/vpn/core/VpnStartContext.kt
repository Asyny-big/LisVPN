package com.lisvpn.android.vpn.core

import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.Server
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local handoff between [VpnConnectionController] and [LisVpnService].
 *
 * Some of the data the service needs to do an AUTO-mode connect — most importantly the list of
 * candidate [Server] objects so it can drive [com.lisvpn.android.core.domain.repository.AutoOptimizerRepository.runPreflight]
 * — can't realistically be packed into the Intent extras (Server is a sealed-class hierarchy
 * with multiple Outbound / Security / Transport variants and was never designed to be
 * Parcelable). Since the controller and the service always run in the same OS process — they
 * are wired via Hilt Singleton bindings — a plain in-memory handoff is both simpler and safer.
 *
 * The contract is:
 *  - The controller calls [stage] before posting `ACTION_START` to the service.
 *  - The service calls [consume] inside `handleStart` exactly once. After that the slot is
 *    cleared so a stale context can never leak into a subsequent connection attempt.
 */
@Singleton
class VpnStartContext @Inject constructor() {
    private val pending = AtomicReference<Pending?>(null)

    fun stage(context: Pending) {
        pending.set(context)
    }

    fun consume(): Pending? = pending.getAndSet(null)

    data class Pending(
        val candidates: List<Server>,
        val smartSelection: Boolean,
        val realConfigJson: String,
        val preflightConfigJson: String?,
        val appRules: AppRules,
        val displayName: String?,
    )
}
