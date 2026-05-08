package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import javax.inject.Inject
import timber.log.Timber

/**
 * App-side AUTO bootstrap ordering. Returns a stable first server quickly; the background optimizer
 * later switches the running selector after real in-tunnel checks.
 *
 * If no cache or historical scores are available yet (cold start), stable/manual tags win first,
 * then original subscription order.
 */
class SelectBestServerUseCase @Inject constructor(
    private val healthRepository: ServerHealthRepository,
) {
    suspend operator fun invoke(servers: List<Server>, limit: Int): List<Server> {
        if (servers.isEmpty()) return emptyList()
        val ranked = healthRepository.rank(servers, limit)
        Timber.i(
            "Auto server selection result: requested=%d ranked=%d selected=%s",
            servers.size,
            ranked.size,
            ranked.joinToString { it.displayName },
        )
        return ranked
    }
}
