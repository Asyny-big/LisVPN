package com.lisvpn.android.feature.home

import com.lisvpn.android.core.designsystem.component.StatusOrbState
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Server
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
    val connectionMode: HomeConnectionMode,
    val servers: List<HomeServerOption>,
    val statusMessage: String? = null,
    val optimizer: AutoOptimizerUiStatus = AutoOptimizerUiStatus.Idle,
) {

    fun withOptimizerStatus(status: AutoOptimizerUiStatus): HomeUiState {
        if (status == optimizer) return this
        // We overlay a generic line while AUTO filters, validates and tests candidates. Detailed
        // server/speed telemetry stays in logs only.
        val statusLine = status.toSubtitleLine()
        val nextSubtitle = if (statusLine != null &&
            (orb == StatusOrbState.Connecting || orb == StatusOrbState.Connected)
        ) {
            statusLine
        } else {
            subtitle
        }
        return copy(optimizer = status, subtitle = nextSubtitle)
    }
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
            connectionMode = HomeConnectionMode.Auto,
            servers = emptyList(),
            statusMessage = null,
        )

        fun from(
            vpn: VpnState,
            profileName: String?,
            allServers: List<Server>,
            connectionMode: HomeConnectionMode,
            selectedServerId: String?,
        ): HomeUiState {
            // Show every parsed server in the manual list. The previous "Telegram-only" name
            // heuristic was filtering out perfectly usable VPN entries (the user reported the
            // 🇪🇪 Эстония Telegram server missing from the list while the same subscription
            // showed it in other clients).
            val selected = allServers.firstOrNull { it.id == selectedServerId }
            val hasServers = allServers.isNotEmpty()
            val base = Empty.copy(
                activeProfileName = profileName,
                activeServerCount = allServers.size,
                connectionMode = connectionMode,
                servers = allServers.map { server ->
                    HomeServerOption(
                        id = server.id,
                        title = server.displayName,
                        subtitle = server.uiSubtitle(),
                        selected = server.id == selected?.id,
                    )
                },
            )
            return when (vpn) {
            VpnState.Idle -> base.copy(
                title = if (hasServers) "Готов к подключению" else "Импортируйте подписку",
                subtitle = when {
                    !hasServers -> "Чтобы начать пользоваться LisVPN"
                    connectionMode == HomeConnectionMode.Auto -> "Авто · лучший из ${allServers.size}"
                    selected != null -> "Вручную · ${selected.displayName}"
                    else -> "Выберите сервер"
                },
                canConnect = hasServers,
                showImportPrompt = !hasServers,
            )
            VpnState.Disconnecting -> base.copy(
                orb = StatusOrbState.Connecting,
                title = "Отключение…",
                subtitle = "Закрываем VPN-туннель",
                canConnect = false,
                canDisconnect = false,
            )
            VpnState.Preparing -> base.copy(
                orb = StatusOrbState.Connecting,
                title = "Запрос разрешения…",
                subtitle = "Разрешите VPN-подключение",
                canConnect = false,
                canDisconnect = false,
            )
            is VpnState.Connecting -> base.copy(
                orb = StatusOrbState.Connecting,
                title = "Подключение…",
                subtitle = vpn.serverDisplayName ?: "Поиск лучшего маршрута",
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Connected -> base.copy(
                orb = StatusOrbState.Connected,
                title = "VPN включён",
                subtitle = buildString {
                    append(vpn.server.displayName)
                    vpn.pingMs?.let { append(" · "); append(it); append(" ms") }
                },
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Reconnecting -> base.copy(
                orb = StatusOrbState.Connecting,
                title = "Переподключение",
                subtitle = vpn.previousServerDisplayName ?: "Восстанавливаем туннель",
                canConnect = false,
                canDisconnect = true,
            )
            is VpnState.Error -> base.copy(
                orb = StatusOrbState.Error,
                title = "Не удалось подключиться",
                subtitle = vpn.detail ?: vpn.reason.toLocalizedReason(),
                canConnect = hasServers,
                canDisconnect = false,
                errorMessage = vpn.detail,
            )
            }
        }
    }
}

enum class HomeConnectionMode { Auto, Manual }

data class HomeServerOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean,
)

/**
 * Lightweight UI projection of AUTO progress so the Home screen can show real server validation
 * and mini speed-test progress instead of an opaque "Connecting" state.
 */
sealed interface AutoOptimizerUiStatus {
    data object Idle : AutoOptimizerUiStatus

    data class Probing(
        val current: Int,
        val total: Int,
        val serverDisplayName: String,
        val lastSpeedKbps: Long?,
        val lastServerDisplayName: String?,
    ) : AutoOptimizerUiStatus

    data class Done(
        val bestServerDisplayName: String,
        val bestSpeedKbps: Long?,
        val tested: Int,
    ) : AutoOptimizerUiStatus
}

fun AutoOptimizerUiStatus.toSubtitleLine(): String? = when (this) {
    AutoOptimizerUiStatus.Idle -> null
    is AutoOptimizerUiStatus.Probing -> progressLine()
    is AutoOptimizerUiStatus.Done -> null
}

private fun AutoOptimizerUiStatus.Probing.progressLine(): String {
    if (total <= 1) return "Выбираем лучший сервер…"
    if (total > AUTO_VISIBLE_PROGRESS_LIMIT) return "Анализируем доступные серверы…"
    val safeCurrent = current.coerceIn(1, total)
    return "Проверяем серверы: $safeCurrent/$total"
}

private const val AUTO_VISIBLE_PROGRESS_LIMIT = 12

private fun Server.uiSubtitle(): String = listOfNotNull(
    countryCode?.uppercase(),
    outbound.protocolLabel(),
).joinToString(" · ")

private fun Outbound.protocolLabel(): String = when (this) {
    is Outbound.Vless -> "VLESS"
    is Outbound.Vmess -> "VMess"
    is Outbound.Trojan -> "Trojan"
    is Outbound.Shadowsocks -> "Shadowsocks"
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
