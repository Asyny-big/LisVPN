package com.lisvpn.android.core.data.repository

import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.core.domain.repository.VpnRepository
import com.lisvpn.android.vpn.core.VpnConnectionController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Domain-facing facade. Reads current per-app rules and delegates the actual VPN runtime to
 * [VpnConnectionController] (which in turn fires Intents at [com.lisvpn.android.vpn.core.LisVpnService]).
 */
@Singleton
class VpnRepositoryImpl @Inject constructor(
    private val controller: VpnConnectionController,
    private val appRulesRepository: AppRulesRepository,
) : VpnRepository {

    override val state: StateFlow<VpnState> get() = controller.state

    override suspend fun start(
        servers: List<Server>,
        smartSelection: Boolean,
        permission: VpnPermissionHandle,
    ): AppResult<Unit> {
        val rules = runCatching { appRulesRepository.observe().first() }.getOrDefault(AppRules.Default)
        return controller.start(
            servers = servers,
            smartSelection = smartSelection,
            appRules = rules,
            permission = permission,
        ).fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Vpn(it.message ?: "start failed"), it) },
        )
    }

    override suspend fun stop(): AppResult<Unit> {
        return controller.stop().fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Vpn(it.message ?: "stop failed"), it) },
        )
    }

    override fun acknowledgeError() = controller.acknowledgeError()
}
