package com.lisvpn.android.vpn.health

import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock

/**
 * Phase-1 stub. Real implementation (TCP ping + TLS handshake + 204 RTT) lands together
 * with WorkManager scheduling in `:vpn:health` Phase 5.
 */
@Singleton
class HealthProbe @Inject constructor() {

    suspend fun probe(server: Server): HealthSnapshot = HealthSnapshot(
        serverId = server.id,
        timestamp = Clock.System.now(),
        tcpHandshakeMs = null,
        tlsHandshakeMs = null,
        httpRttMs = null,
        success = false,
        networkType = HealthSnapshot.NetworkType.Unknown,
    )
}
