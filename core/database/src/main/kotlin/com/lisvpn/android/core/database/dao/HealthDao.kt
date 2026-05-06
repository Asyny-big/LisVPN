package com.lisvpn.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lisvpn.android.core.database.entity.HealthSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Insert
    suspend fun insert(snapshot: HealthSnapshotEntity): Long

    @Query("SELECT * FROM health_snapshot WHERE serverId = :serverId ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(serverId: String, limit: Int): Flow<List<HealthSnapshotEntity>>

    @Query("DELETE FROM health_snapshot WHERE timestampMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long): Int
}
