package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.common.result.appResult
import com.lisvpn.android.core.domain.model.HealthScore
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * MVP stub — no probing yet. Returns input servers in original order so SelectBestServerUseCase
 * still produces a deterministic ranking.
 */
@Singleton
class ServerHealthRepositoryImpl @Inject constructor() : ServerHealthRepository {

    private val _scores = MutableStateFlow<List<HealthScore>>(emptyList())

    override fun observeScores(): Flow<List<HealthScore>> = _scores

    override fun observeScore(serverId: String): Flow<HealthScore?> =
        _scores.map { list -> list.firstOrNull { it.serverId == serverId } }

    override suspend fun record(snapshot: HealthSnapshot) = Unit

    override suspend fun probe(server: Server): AppResult<HealthSnapshot> = appResult {
        HealthSnapshot(
            serverId = server.id,
            timestamp = kotlinx.datetime.Clock.System.now(),
            tcpHandshakeMs = null,
            tlsHandshakeMs = null,
            httpRttMs = null,
            success = false,
            networkType = HealthSnapshot.NetworkType.Unknown,
        )
    }

    override suspend fun rank(servers: List<Server>, limit: Int): List<Server> =
        servers.take(limit)
}
