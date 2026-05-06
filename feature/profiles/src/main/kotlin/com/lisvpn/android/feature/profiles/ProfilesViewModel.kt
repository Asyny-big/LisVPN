package com.lisvpn.android.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Profile
import com.lisvpn.android.core.domain.model.ProfileSource
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val importing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val profiles = profileRepository.observeProfiles()
    private val activeServers = profileRepository.observePrimaryProfile().flatMapLatest { profile ->
        profile?.let { profileRepository.observeServers(it.id) } ?: flowOf(emptyList())
    }

    val uiState: StateFlow<ProfilesUiState> = combine(profiles, activeServers, importing, message) { profileList, servers, busy, msg ->
        ProfilesUiState(
            profiles = profileList,
            activeServers = servers,
            importing = busy,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun consumeMessage() {
        message.value = null
    }

    fun importSubscription(url: String) {
        val trimmed = url.trim()
        if (!isSupportedImportInput(trimmed)) {
            message.value = "Введите HTTPS subscription URL или VLESS URI"
            return
        }
        if (importing.value) return
        viewModelScope.launch {
            importing.value = true
            val source = if (trimmed.startsWith("vless://", ignoreCase = true)) {
                ProfileSource.SingleUri(trimmed)
            } else {
                ProfileSource.SubscriptionUrl(trimmed)
            }
            when (val result = profileRepository.import(source)) {
                is AppResult.Success -> message.value = "Импортировано: ${result.value.name}"
                is AppResult.Failure -> message.value = result.error.toUiMessage()
            }
            importing.value = false
        }
    }

    fun refresh(profileId: String) {
        if (importing.value) return
        viewModelScope.launch {
            importing.value = true
            when (val result = profileRepository.refresh(profileId)) {
                is AppResult.Success -> message.value = "Подписка обновлена"
                is AppResult.Failure -> message.value = result.error.toUiMessage()
            }
            importing.value = false
        }
    }

    fun setActive(profileId: String) {
        viewModelScope.launch {
            when (val result = profileRepository.setPrimary(profileId)) {
                is AppResult.Success -> message.value = "Профиль выбран"
                is AppResult.Failure -> message.value = result.error.toUiMessage()
            }
        }
    }

    fun delete(profileId: String) {
        viewModelScope.launch {
            when (val result = profileRepository.delete(profileId)) {
                is AppResult.Success -> message.value = "Профиль удалён"
                is AppResult.Failure -> message.value = result.error.toUiMessage()
            }
        }
    }

    private fun isSupportedImportInput(value: String): Boolean {
        if (value.startsWith("vless://", ignoreCase = true)) return true
        return value.startsWith("https://", ignoreCase = true)
    }
}

data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    val activeServers: List<Server> = emptyList(),
    val importing: Boolean = false,
    val message: String? = null,
)

private fun AppError.toUiMessage(): String = when (this) {
    AppError.Network -> "Нет сети или DNS не отвечает"
    AppError.Timeout -> "Сервер подписки не ответил вовремя"
    is AppError.Server -> "Сервер вернул ошибку $statusCode"
    AppError.Unauthorized -> "Подписка недоступна"
    is AppError.Parse -> reason
    is AppError.Vpn -> reason
    AppError.NotFound -> "Профиль не найден"
    is AppError.Unknown -> reason ?: "Неизвестная ошибка"
}
