package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.repository.VpnRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.firstOrNull

/**
 * Orchestrates the "Connect" tap:
 *  1. Pull the active profile.
 *  2. Ask [SelectBestServerUseCase] for a ranked subset (urltest will further refine in real time).
 *  3. Delegate to [VpnRepository.start] with a permission handle supplied by the activity.
 */
class ConnectVpnUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val selectBestServer: SelectBestServerUseCase,
    private val vpnRepository: VpnRepository,
) {
    suspend operator fun invoke(permission: VpnPermissionHandle, smartSelection: Boolean = true): AppResult<Unit> {
        val profile = profileRepository.observePrimaryProfile().firstOrNull()
            ?: return AppResult.Failure(AppError.Vpn("No active profile"))
        val servers = profileRepository.observeServers(profile.id).firstOrNull().orEmpty()
        if (servers.isEmpty()) return AppResult.Failure(AppError.Vpn("Profile has no servers"))

        val selected = if (smartSelection) selectBestServer(servers, limit = SMART_LIMIT) else listOf(servers.first())
        if (selected.isEmpty()) return AppResult.Failure(AppError.Vpn("Profile has no available servers"))
        return vpnRepository.start(
            servers = selected,
            smartSelection = smartSelection,
            permission = permission,
        )
    }

    private companion object {
        const val SMART_LIMIT = 4
    }
}
