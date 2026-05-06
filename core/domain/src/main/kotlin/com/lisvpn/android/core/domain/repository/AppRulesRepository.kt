package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.AppRules
import kotlinx.coroutines.flow.Flow

interface AppRulesRepository {
    fun observe(): Flow<AppRules>
    suspend fun update(rules: AppRules): AppResult<Unit>
}
