package com.lisvpn.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lisvpn.android.core.database.entity.ProfileEntity
import com.lisvpn.android.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY isPrimary DESC, createdAtMs ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE isPrimary = 1 LIMIT 1")
    fun observePrimaryProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM server WHERE profileId = :profileId ORDER BY createdAtMs ASC")
    fun observeServers(profileId: String): Flow<List<ServerEntity>>

    @Query("SELECT * FROM server ORDER BY createdAtMs ASC")
    fun observeAllServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM profile WHERE id = :profileId LIMIT 1")
    suspend fun getProfile(profileId: String): ProfileEntity?

    @Query("SELECT * FROM profile WHERE sourceType = :sourceType AND sourceValue = :sourceValue LIMIT 1")
    suspend fun getProfileBySource(sourceType: String, sourceValue: String): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profile")
    suspend fun countProfiles(): Int

    @Query("SELECT COUNT(*) FROM profile WHERE isPrimary = 1")
    suspend fun countPrimaryProfiles(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerEntity>)

    @Query("DELETE FROM server WHERE profileId = :profileId")
    suspend fun deleteServers(profileId: String)

    @Query("DELETE FROM profile WHERE id = :profileId")
    suspend fun deleteProfileRow(profileId: String)

    @Query("UPDATE profile SET isPrimary = 0 WHERE isPrimary = 1")
    suspend fun clearPrimary()

    @Query("UPDATE profile SET isPrimary = CASE WHEN id = :profileId THEN 1 ELSE 0 END")
    suspend fun setPrimaryOnly(profileId: String)

    @Transaction
    suspend fun replaceProfileServers(profile: ProfileEntity, servers: List<ServerEntity>) {
        upsertProfile(profile)
        deleteServers(profile.id)
        if (servers.isNotEmpty()) insertServers(servers)
    }

    @Transaction
    suspend fun importProfile(profile: ProfileEntity, servers: List<ServerEntity>, makePrimary: Boolean) {
        if (makePrimary) clearPrimary()
        replaceProfileServers(profile.copy(isPrimary = makePrimary || profile.isPrimary), servers)
    }

    @Transaction
    suspend fun setPrimary(profileId: String): Boolean {
        if (getProfile(profileId) == null) return false
        setPrimaryOnly(profileId)
        return true
    }

    @Transaction
    suspend fun deleteProfile(profileId: String) {
        deleteProfileRow(profileId)
        if (countProfiles() > 0 && countPrimaryProfiles() == 0) {
            val remaining = firstProfileId()
            if (remaining != null) setPrimaryOnly(remaining)
        }
    }

    @Query("SELECT id FROM profile ORDER BY createdAtMs ASC LIMIT 1")
    suspend fun firstProfileId(): String?
}
