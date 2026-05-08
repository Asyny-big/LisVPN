package com.lisvpn.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lisvpn.android.core.database.entity.SmartServerCacheEntity

@Dao
interface SmartServerCacheDao {

    @Query(
        """
        SELECT * FROM smart_server_cache
        WHERE networkKey = :networkKey AND serverId IN (:serverIds)
        """,
    )
    suspend fun histories(networkKey: String, serverIds: List<String>): List<SmartServerCacheEntity>

    @Query(
        """
        SELECT * FROM smart_server_cache
        WHERE networkKey = :networkKey AND serverId = :serverId
        LIMIT 1
        """,
    )
    suspend fun history(networkKey: String, serverId: String): SmartServerCacheEntity?

    @Query(
        """
        SELECT * FROM smart_server_cache
        WHERE networkKey = :networkKey
        ORDER BY lastScore DESC, successCount DESC, updatedAtMs DESC
        LIMIT :limit
        """,
    )
    suspend fun bestForNetwork(networkKey: String, limit: Int): List<SmartServerCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SmartServerCacheEntity)

    @Query("DELETE FROM smart_server_cache WHERE updatedAtMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long): Int
}
