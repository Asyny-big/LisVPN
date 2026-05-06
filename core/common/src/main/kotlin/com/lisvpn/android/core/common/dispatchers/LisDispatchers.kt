package com.lisvpn.android.core.common.dispatchers

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

/**
 * DI qualifiers for coroutine dispatchers. Keeps tests substitutable (e.g. `StandardTestDispatcher`)
 * and prevents accidental usage of `Dispatchers.Default` in code paths that should be IO-bound.
 */
@Qualifier @Retention(BINARY) annotation class IoDispatcher
@Qualifier @Retention(BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(BINARY) annotation class MainDispatcher
@Qualifier @Retention(BINARY) annotation class MainImmediateDispatcher

/**
 * Lightweight wrapper passed by DI when callers need the full set without four `@Inject` qualifiers.
 */
data class LisDispatchers(
    val io: kotlinx.coroutines.CoroutineDispatcher,
    val default: kotlinx.coroutines.CoroutineDispatcher,
    val main: kotlinx.coroutines.CoroutineDispatcher,
    val mainImmediate: kotlinx.coroutines.CoroutineDispatcher,
)
