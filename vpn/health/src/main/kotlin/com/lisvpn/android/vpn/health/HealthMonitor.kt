package com.lisvpn.android.vpn.health

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class HealthMonitor @Inject constructor(
    private val tunnelValidationWorker: TunnelValidationWorker,
) {

    fun monitor(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<HealthCheckResult> = flow {
        delay(INITIAL_DELAY_MS)
        while (true) {
            emit(tunnelValidationWorker.quickGenerate204())
            delay(intervalMs)
        }
    }

    suspend fun checkNow(): HealthCheckResult = tunnelValidationWorker.quickGenerate204()

    private companion object {
        const val INITIAL_DELAY_MS = 8_000L
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
