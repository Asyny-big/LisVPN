package com.lisvpn.android.core.common.time

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock as KClock
import kotlinx.datetime.Instant

/**
 * Inject-able clock so domain code is fully deterministic in tests.
 * Always prefer [LisClock] over `System.currentTimeMillis()` or `Clock.System.now()` directly.
 */
fun interface LisClock {
    fun now(): Instant
}

@Singleton
class SystemLisClock @Inject constructor() : LisClock {
    override fun now(): Instant = KClock.System.now()
}
