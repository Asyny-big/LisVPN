package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Profile
import com.lisvpn.android.core.domain.model.ProfileSource
import com.lisvpn.android.core.domain.model.Server
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {

    /** All profiles known to the app. */
    fun observeProfiles(): Flow<List<Profile>>

    suspend fun listSubscriptionProfiles(): AppResult<List<Profile>>

    /** Servers belonging to a given profile. */
    fun observeServers(profileId: String): Flow<List<Server>>

    /** All servers across profiles, used by the smart-selection use case. */
    fun observeAllServers(): Flow<List<Server>>

    /** The user-selected primary profile (drives the Home screen). */
    fun observePrimaryProfile(): Flow<Profile?>

    suspend fun get(profileId: String): AppResult<Profile>

    /**
     * Import or update a profile from any [ProfileSource]. Idempotent on URL/URI: existing
     * profile with the same source is refreshed, server diff is computed and persisted.
     */
    suspend fun import(source: ProfileSource): AppResult<Profile>

    /** Manual refresh for subscription-backed profiles. */
    suspend fun refresh(profileId: String): AppResult<Profile>

    suspend fun setPrimary(profileId: String): AppResult<Unit>

    suspend fun delete(profileId: String): AppResult<Unit>
}
