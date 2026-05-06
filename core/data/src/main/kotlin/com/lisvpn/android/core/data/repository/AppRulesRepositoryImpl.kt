package com.lisvpn.android.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.common.result.appResult
import com.lisvpn.android.core.datastore.PreferencesDataStoreQualifier
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AppRulesRepositoryImpl @Inject constructor(
    @PreferencesDataStoreQualifier private val dataStore: DataStore<Preferences>,
) : AppRulesRepository {

    override fun observe(): Flow<AppRules> = dataStore.data.map { prefs ->
        val mode = prefs[KEY_MODE]?.let { runCatching { AppRules.Mode.valueOf(it) }.getOrNull() }
            ?: AppRules.Mode.Off
        val packages = prefs[KEY_PACKAGES] ?: emptySet()
        AppRules(mode = mode, packages = packages)
    }

    override suspend fun update(rules: AppRules): AppResult<Unit> = appResult {
        dataStore.edit { prefs ->
            prefs[KEY_MODE] = rules.mode.name
            prefs[KEY_PACKAGES] = rules.packages
        }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("apprules.mode")
        val KEY_PACKAGES = stringSetPreferencesKey("apprules.packages")
    }
}
