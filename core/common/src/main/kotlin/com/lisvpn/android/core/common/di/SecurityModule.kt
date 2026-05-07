package com.lisvpn.android.core.common.di

import com.lisvpn.android.core.common.security.SecretBox
import com.lisvpn.android.core.common.security.TinkSecretBox
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the default Tink-backed [SecretBox] implementation for all feature modules.
 *
 * Keeping the binding here (rather than in :core:data) lets any module depend on [SecretBox]
 * without pulling in Tink transitively through an `api` dependency.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecretBox(impl: TinkSecretBox): SecretBox
}
