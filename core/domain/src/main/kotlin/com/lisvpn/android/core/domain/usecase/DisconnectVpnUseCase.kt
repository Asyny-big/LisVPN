package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject

class DisconnectVpnUseCase @Inject constructor(
    private val autoOptimizerRepository: AutoOptimizerRepository,
    private val vpnRepository: VpnRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        autoOptimizerRepository.cancel()
        return vpnRepository.stop()
    }
}
