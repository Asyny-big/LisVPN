package com.lisvpn.android.di

import com.lisvpn.android.BuildConfig
import com.lisvpn.android.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges flavor-specific build values (BuildConfig) to the network module's [NetworkConfig].
 * Lives in `:app` so `:core:network` stays free of generated build classes.
 *
 * Certificate pins are intentionally left empty for the dev flavor (3x-ui staging often uses
 * Let's Encrypt rotation). For prod, populate the list once the lisvpn.ru certificate fingerprint
 * is finalised — see ARCHITECTURE.md §9.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppNetworkModule {

    @Provides
    @Singleton
    fun provideNetworkConfig(): NetworkConfig = NetworkConfig(
        backendBaseUrl = BuildConfig.BACKEND_BASE_URL,
        defaultUserAgent = BuildConfig.DEFAULT_USER_AGENT,
        strictPinning = BuildConfig.STRICT_CERTIFICATE_PINNING,
        certificatePins = if (BuildConfig.STRICT_CERTIFICATE_PINNING) {
            // TODO(prod): replace with the real lisvpn.ru SubjectPublicKeyInfo SHA-256 fingerprint
            //              once certificate ownership is established.
            emptyList()
        } else emptyList(),
        enableHttpLogging = BuildConfig.DEBUG,
    )
}
