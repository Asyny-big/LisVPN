package com.lisvpn.android.feature.home

import com.lisvpn.android.core.designsystem.component.StatusOrbState
import com.lisvpn.android.core.domain.model.VpnState

/**
 * Immutable view-model state for the Home screen.
 * The mapping from [VpnState] is centralised here so the Composable stays trivially testable.
 */
data class HomeUiState(
    val orb: StatusOrbState,
    val title: String,
    val subtitle: String?,
    val canConnect: Boolean,
    val canDisconnect: Boolean,
    val showImportPrompt: Boolean,
    val errorMessage: String?,
    val activeProfileName: String?,
    val activeServerCount: Int,
) {
    companion object {
        val Empty = HomeUiState(
            orb = StatusOrbState.Idle,
            title = "Готов к подключению",
            subtitle = null,
            canConnect = true,
            canDisconnect = false,
            showImportPrompt = false,
            errorMessage = null,
            activeProfileName = null,
            activeServerCount = 0,
        )

        fun from(vpn: VpnState, profileName: String?, serverCount: Int): HomeUiState = when (vpn) {
            VpnState.Idle -> Empty.copy(
                title = if (profileName != null) "Готов к подключению" else "Импортируйте подписку",
                subtitle = profileName?.let { "$it · серверов: $serverCount" } ?: "Чтобы начать пользоваться LisVPN",
                canConnect = profileName != null,
                showImportPrompt = profileName == null,
                activeProfileName = profileName,
                activeServerCount = serverCount,
            )
            VpnState.Disconnecting -> Empty.copy(
                orb = StatusOrbState.Connecting,
                title = "Отключение…",
                subtitle = "Закрываем VPN-туннель",
                canConnect = false,
                canDisconnect = false,
            )
            VpnState.Preparing -> Empty.copy(
                orb = StatusOrbState.Connecting,
                title = "Запрос разрешения…",
                subtitle = "Разрешите VPN-подключение",
                canConnect = false,
                canDisconnect = false,
            )
            is VpnState.Connecting -> Empty.copy(
                orb = StatusOrbState.Connecting,
                title = "Подключение…",
                subtitle = vpn.serverDisplayName ?: "Поиск лучшего маршрута",
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Connected -> Empty.copy(
                orb = StatusOrbState.Connected,
                title = "VPN включён",
                subtitle = buildString {
                    append(vpn.server.displayName)
                    vpn.pingMs?.let { append(" · "); append(it); append(" ms") }
                },
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Reconnecting -> Empty.copy(
                orb = StatusOrbState.Connecting,
                title = "Переподключение",
                subtitle = vpn.previousServerDisplayName ?: "Восстанавливаем туннель",
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Error -> Empty.copy(
                orb = StatusOrbState.Error,
                title = "Не удалось подключиться",
                subtitle = vpn.detail ?: vpn.reason.toLocalizedReason(),
                canConnect = profileName != null,
                canDisconnect = false,
                errorMessage = vpn.detail,
                activeProfileName = profileName,
                activeServerCount = serverCount,
            )
        }
    }
}

private fun VpnState.Reason.toLocalizedReason(): String = when (this) {
    VpnState.Reason.PermissionDenied -> "Разрешение VPN не получено"
    VpnState.Reason.PermissionRevoked -> "Разрешение отозвано системой"
    VpnState.Reason.NoProfile -> "Нет активной подписки"
    VpnState.Reason.ConfigInvalid -> "Конфигурация подписки повреждена"
    VpnState.Reason.TunnelEstablishFailed -> "Не удалось открыть туннель"
    VpnState.Reason.StartFailed -> "Сбой запуска движка"
    VpnState.Reason.SubscriptionExpired -> "Подписка истекла"
    VpnState.Reason.DeviceLimitReached -> "Превышен лимит устройств"
    VpnState.Reason.NetworkUnavailable -> "Нет сети"
    VpnState.Reason.Unknown -> "Произошла неизвестная ошибка"
}
