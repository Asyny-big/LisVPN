package com.lisvpn.android.core.network.di

import com.lisvpn.android.core.network.KtorClientFactory
import com.lisvpn.android.core.network.LisHttpClient
import com.lisvpn.android.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

/**
 * Provides the singleton [HttpClient]. The [NetworkConfig] dependency is supplied by the
 * `:app` module so flavor-specific values (BuildConfig) stay out of the network module.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @LisHttpClient
    fun provideHttpClient(factory: KtorClientFactory, config: NetworkConfig): HttpClient =
        factory.create(config)
}
