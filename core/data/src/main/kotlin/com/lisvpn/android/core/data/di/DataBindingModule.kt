package com.lisvpn.android.core.data.di

import com.lisvpn.android.core.data.repository.AppRulesRepositoryImpl
import com.lisvpn.android.core.data.repository.InstalledAppsRepositoryImpl
import com.lisvpn.android.core.data.repository.ProfileRepositoryImpl
import com.lisvpn.android.core.data.repository.ServerHealthRepositoryImpl
import com.lisvpn.android.core.data.repository.VpnRepositoryImpl
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.InstalledAppsRepository
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.ServerHealthRepository
import com.lisvpn.android.core.domain.repository.VpnRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single Hilt module wiring all domain repositories to their implementations.
 * Centralised here so swapping an implementation is a single-line change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingModule {

    @Binds @Singleton
    abstract fun bindVpnRepository(impl: VpnRepositoryImpl): VpnRepository

    @Binds @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds @Singleton
    abstract fun bindAppRulesRepository(impl: AppRulesRepositoryImpl): AppRulesRepository

    @Binds @Singleton
    abstract fun bindServerHealthRepository(impl: ServerHealthRepositoryImpl): ServerHealthRepository

    @Binds @Singleton
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository
}
