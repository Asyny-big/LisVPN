package com.lisvpn.android.feature.home

import com.lisvpn.android.core.designsystem.component.StatusOrbState
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AutoOptimizerStage

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
    val manualCheckInProgress: Boolean = false,
    val statusMessage: String? = null,
    val optimizer: AutoOptimizerUiStatus = AutoOptimizerUiStatus.Idle,
    val autoProgress: HomeAutoProgressUi? = null,
) {

    fun withOptimizerStatus(status: AutoOptimizerUiStatus): HomeUiState {
        if (status == optimizer) return this
        val progress = status.toAutoProgress()
        val statusLine = status.toSubtitleLine()
        val nextSubtitle = if (statusLine != null && orb == StatusOrbState.Connecting) {
            statusLine
        } else {
            subtitle
        }
        return copy(optimizer = status, autoProgress = progress, subtitle = nextSubtitle)
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
            manualCheckInProgress = false,
            statusMessage = null,
            autoProgress = null,
        )

        fun from(
            vpn: VpnState,
            profileName: String?,
            allServers: List<Server>,
            connectionMode: HomeConnectionMode,
            selectedServerId: String?,
            manualChecks: Map<String, ManualServerCheckUi> = emptyMap(),
        ): HomeUiState {
            // Show every parsed server in the manual list. The previous "Telegram-only" name
            // heuristic was filtering out perfectly usable VPN entries (the user reported the
            // 🇪🇪 Эстония Telegram server missing from the list while the same subscription
            // showed it in other clients).
            val selected = allServers.firstOrNull { it.id == selectedServerId }
            val hasServers = allServers.isNotEmpty()
            val checkingServers = manualChecks.values.any { it.checking }
            val base = Empty.copy(
                activeProfileName = profileName,
                activeServerCount = allServers.size,
                connectionMode = connectionMode,
                manualCheckInProgress = checkingServers,
                servers = allServers.map { server ->
                    HomeServerOption(
                        id = server.id,
                        title = server.displayName,
                        subtitle = server.uiSubtitle(),
                        check = manualChecks[server.id],
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
    val check: ManualServerCheckUi? = null,
)

data class ManualServerCheckUi(
    val checking: Boolean = false,
    val reachable: Boolean? = null,
    val pingMs: Int? = null,
) {
    fun label(): String = when {
        checking -> "Проверяем…"
        reachable == true && pingMs != null -> "Пинг: ${pingMs} мс"
        reachable == true -> "Доступен"
        reachable == false -> "Нет ответа"
        else -> "Не проверен"
    }
}

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
        val stage: AutoOptimizerStage,
        val stageMessage: String?,
        val progressPercent: Int?,
        val estimatedRemainingMs: Long?,
        val checked: Int?,
        val reachable: Int?,
        val debugSummary: String?,
    ) : AutoOptimizerUiStatus

    data class Done(
        val bestServerDisplayName: String,
        val bestSpeedKbps: Long?,
        val tested: Int,
        val total: Int?,
        val elapsedMs: Long?,
        val selectionReason: String?,
        val debugSummary: String?,
    ) : AutoOptimizerUiStatus

    data class Failed(
        val reason: String,
        val stage: AutoOptimizerStage?,
        val tested: Int?,
        val total: Int?,
        val debugSummary: String?,
    ) : AutoOptimizerUiStatus
}

data class HomeAutoProgressUi(
    val title: String,
    val detail: String?,
    val currentServer: String?,
    val progressText: String?,
    val progressFraction: Float?,
    val etaText: String?,
    val resultText: String?,
    val debugText: String?,
    val severity: Severity = Severity.Info,
) {
    enum class Severity { Info, Success, Warning, Error }
}

fun AutoOptimizerUiStatus.toSubtitleLine(): String? = when (this) {
    AutoOptimizerUiStatus.Idle -> null
    is AutoOptimizerUiStatus.Probing -> when (stage) {
        AutoOptimizerStage.FastFilter -> "Поиск лучшего маршрута…"
        AutoOptimizerStage.TunnelValidation -> "Тестируем соединение…"
        AutoOptimizerStage.SpeedTest -> "Измеряем скорость…"
        AutoOptimizerStage.SelectingWinner -> "Выбираем быстрый сервер…"
        AutoOptimizerStage.BootstrapRanking -> "Подбираем кандидатов…"
        AutoOptimizerStage.Failover -> "Ищем резервный маршрут…"
    }
    is AutoOptimizerUiStatus.Done -> "Найден быстрый сервер"
    is AutoOptimizerUiStatus.Failed -> null
}

private fun AutoOptimizerUiStatus.toAutoProgress(): HomeAutoProgressUi? = when (this) {
    AutoOptimizerUiStatus.Idle -> null
    is AutoOptimizerUiStatus.Probing -> HomeAutoProgressUi(
        title = stage.title(),
        detail = stageMessage ?: stage.defaultDetail(),
        currentServer = serverDisplayName.takeUnless { it.isBlank() || it.startsWith("Быстрый фильтр") },
        progressText = progressLabel(),
        progressFraction = progressFraction(),
        etaText = estimatedRemainingMs?.formatEta(),
        resultText = bestSoFarLabel(),
        debugText = debugSummary,
    )
    is AutoOptimizerUiStatus.Done -> HomeAutoProgressUi(
        title = "Найден быстрый сервер",
        detail = selectionReason ?: "AUTO выбрал маршрут с лучшим score",
        currentServer = bestServerDisplayName,
        progressText = buildString {
            append("Проверено: ")
            append(tested)
            total?.let { append('/'); append(it) }
            elapsedMs?.formatElapsed()?.let { append(" · "); append(it) }
        },
        progressFraction = 1f,
        etaText = null,
        resultText = bestSpeedKbps?.let { "Скорость: ${it.formatSpeed()}" },
        debugText = debugSummary,
        severity = HomeAutoProgressUi.Severity.Success,
    )
    is AutoOptimizerUiStatus.Failed -> HomeAutoProgressUi(
        title = "AUTO не нашёл рабочий маршрут",
        detail = reason,
        currentServer = null,
        progressText = listOfNotNull(
            tested?.let { done -> total?.let { "$done/$it" } ?: done.toString() },
            stage?.title(),
        ).joinToString(" · ").ifBlank { null },
        progressFraction = null,
        etaText = null,
        resultText = "Попробуйте другую сеть или сервер вручную",
        debugText = debugSummary,
        severity = HomeAutoProgressUi.Severity.Error,
    )
}

private fun AutoOptimizerUiStatus.Probing.progressLabel(): String? {
    val safeTotal = total.coerceAtLeast(0)
    val safeCurrent = current.coerceIn(0, safeTotal.takeIf { it > 0 } ?: current)
    val base = when {
        safeTotal > 0 -> "$safeCurrent/$safeTotal"
        checked != null -> "Проверено: $checked"
        else -> null
    }
    val reachablePart = reachable?.let { "доступно: $it" }
    val percentPart = progressPercent?.let { "$it%" }
    return listOfNotNull(base, reachablePart, percentPart).joinToString(" · ").ifBlank { null }
}

private fun AutoOptimizerUiStatus.Probing.progressFraction(): Float? = when {
    progressPercent != null -> progressPercent.coerceIn(0, 100) / 100f
    total > 0 -> current.coerceIn(0, total).toFloat() / total.toFloat()
    else -> null
}

private fun AutoOptimizerUiStatus.Probing.bestSoFarLabel(): String? {
    val server = lastServerDisplayName ?: return null
    val speed = lastSpeedKbps?.formatSpeed()
    return if (speed == null) "Уже найден: $server" else "Лучший пока: $server · $speed"
}

private fun AutoOptimizerStage.title(): String = when (this) {
    AutoOptimizerStage.BootstrapRanking -> "Подбираем кандидатов"
    AutoOptimizerStage.FastFilter -> "Проверяем доступность"
    AutoOptimizerStage.TunnelValidation -> "Тестируем соединение"
    AutoOptimizerStage.SpeedTest -> "Измеряем скорость"
    AutoOptimizerStage.SelectingWinner -> "Выбираем лучший сервер"
    AutoOptimizerStage.Failover -> "Переключаемся на резерв"
}

private fun AutoOptimizerStage.defaultDetail(): String = when (this) {
    AutoOptimizerStage.BootstrapRanking -> "Учитываем историю успешных подключений"
    AutoOptimizerStage.FastFilter -> "Дешёвые TCP/DNS проверки отсекают недоступные серверы"
    AutoOptimizerStage.TunnelValidation -> "Проверяем реальный интернет через VPN-туннель"
    AutoOptimizerStage.SpeedTest -> "Короткий mini speed test без долгого ожидания"
    AutoOptimizerStage.SelectingWinner -> "Сравниваем latency, стабильность, историю и скорость"
    AutoOptimizerStage.Failover -> "Текущий маршрут нестабилен, ищем рабочий fallback"
}

private fun Long.formatEta(): String {
    val seconds = ((this + 999L) / 1_000L).coerceAtLeast(1L)
    return "осталось ≈ ${seconds} с"
}

private fun Long.formatElapsed(): String {
    val seconds = ((this + 999L) / 1_000L).coerceAtLeast(1L)
    return "${seconds} с"
}

private fun Long.formatSpeed(): String = when {
    this >= 1_000L -> "${this / 1_000}.${((this % 1_000) / 100)} Мбит/с"
    else -> "$this Кбит/с"
}

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
