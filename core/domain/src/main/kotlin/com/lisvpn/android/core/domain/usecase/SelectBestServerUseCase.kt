package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import javax.inject.Inject
import timber.log.Timber

/**
 * App-side AUTO bootstrap ordering.
 *
 * This use case deliberately does not run network I/O anymore. The previous app-side live probe
 * duplicated the service-side fast filter and could spend several seconds in ProtocolProbe before
 * Android even started the VPN service. AUTO now feels instant: this stage only orders candidates
 * by cache/history/stable tags, then LisVpnService performs the cancellable fast filter and real
 * tunnel validation with visible progress.
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
        Timber.i(
            "Auto server selection result: requested=%d ranked=%d selected=%s",
            servers.size,
            ranked.size,
            ranked.joinToString { it.displayName },
        )
        return ranked
    }
}
