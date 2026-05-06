package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

class ObserveVpnStateUseCase @Inject constructor(
    private val vpnRepository: VpnRepository,
) {
    operator fun invoke(): StateFlow<VpnState> = vpnRepository.state
}
