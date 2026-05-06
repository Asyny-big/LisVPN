package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import javax.inject.Inject

/**
 * App-side ranking. Returns up to [limit] best-scored servers; sing-box `urltest` outbound performs
 * the real-time selection in the kernel afterwards.
 *
 * If no scores are available yet (cold start), returns servers with [Server.Tag.Primary] first,
 * then in original order.
 */
class SelectBestServerUseCase @Inject constructor(
    private val healthRepository: ServerHealthRepository,
) {
    suspend operator fun invoke(servers: List<Server>, limit: Int): List<Server> {
        if (servers.isEmpty()) return emptyList()
        val ranked = healthRepository.rank(servers, limit)
        if (ranked.isNotEmpty()) return ranked

        // Fallback: prioritise primary-tagged servers.
        return servers
            .sortedByDescending { Server.Tag.Primary in it.tags }
            .take(limit)
    }
}
