package com.lisvpn.android.core.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.repository.InstalledApp
import com.lisvpn.android.core.domain.repository.InstalledAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

@Singleton
class InstalledAppsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : InstalledAppsRepository {

    override fun observeInstalledApps(includeSystem: Boolean): Flow<List<InstalledApp>> = flow {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val infos = pm.getInstalledApplications(flags)
        val mapped = infos
            .asSequence()
            .filter { includeSystem || !it.isSystemApp() }
            .map { ai ->
                InstalledApp(
                    packageName = ai.packageName,
                    appName = ai.loadLabel(pm).toString(),
                    isSystem = ai.isSystemApp(),
                )
            }
            .sortedBy { it.appName.lowercase() }
            .toList()
        emit(mapped)
    }.flowOn(ioDispatcher)

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
