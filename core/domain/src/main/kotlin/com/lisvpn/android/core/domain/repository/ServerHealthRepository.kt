package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.HealthScore
import com.lisvpn.android.core.domain.model.HealthSnapshot
import com.lisvpn.android.core.domain.model.Server
import kotlinx.coroutines.flow.Flow

interface ServerHealthRepository {

    fun observeScores(): Flow<List<HealthScore>>

    fun observeScore(serverId: String): Flow<HealthScore?>

    /** Records a single probe result (one row per attempt; aggregation is computed). */
    suspend fun record(snapshot: HealthSnapshot)

    /**
     * Triggers an opportunistic probe of the given server. Implementation may choose to
     * dedupe against in-flight probes.
     */
    suspend fun probe(server: Server): AppResult<HealthSnapshot>

    /** Returns top-N servers by current rolling score, optionally filtered. */
    suspend fun rank(servers: List<Server>, limit: Int): List<Server>
}
