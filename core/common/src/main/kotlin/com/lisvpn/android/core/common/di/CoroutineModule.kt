package com.lisvpn.android.core.common.di

import com.lisvpn.android.core.common.dispatchers.DefaultDispatcher
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.common.dispatchers.LisDispatchers
import com.lisvpn.android.core.common.dispatchers.MainDispatcher
import com.lisvpn.android.core.common.dispatchers.MainImmediateDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main
    @Provides @MainImmediateDispatcher fun mainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    fun dispatchers(
        @IoDispatcher io: CoroutineDispatcher,
        @DefaultDispatcher default: CoroutineDispatcher,
        @MainDispatcher main: CoroutineDispatcher,
        @MainImmediateDispatcher mainImmediate: CoroutineDispatcher,
    ): LisDispatchers = LisDispatchers(io, default, main, mainImmediate)

    /**
     * Application-scoped supervised scope. Long-running work that must survive UI lifecycle events
     * (e.g. subscription refresh, health probing) is launched here. Crucially: NOT used for VPN
     * runtime state — that lives inside [com.lisvpn.android.vpn.core.LisVpnService].
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
