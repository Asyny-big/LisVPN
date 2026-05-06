package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject

class DisconnectVpnUseCase @Inject constructor(
    private val vpnRepository: VpnRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> = vpnRepository.stop()
}
