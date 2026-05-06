package com.lisvpn.android.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** Implemented in `:core:data` via PackageManager — required by feature/splittunnel UI. */
interface InstalledAppsRepository {
    fun observeInstalledApps(includeSystem: Boolean = false): Flow<List<InstalledApp>>
}

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
)
