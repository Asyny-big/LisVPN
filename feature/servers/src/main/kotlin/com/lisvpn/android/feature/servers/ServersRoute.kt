package com.lisvpn.android.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

const val SERVERS_ROUTE = "servers"

fun NavGraphBuilder.serversRoute(onBack: () -> Unit) {
    composable(SERVERS_ROUTE) { ServersRoute(onBack = onBack) }
}

@HiltViewModel
class ServersViewModel @Inject constructor(
    profileRepository: ProfileRepository,
) : ViewModel() {
    val servers = profileRepository.observePrimaryProfile().flatMapLatest { profile ->
        profile?.let { profileRepository.observeServers(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
private fun ServersRoute(
    onBack: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    ServersScreen(servers = servers, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreen(servers: List<Server>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (servers.isEmpty()) {
                item { Text("Серверы появятся после импорта подписки", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(servers, key = { it.id }) { server -> ServerCard(server) }
        }
    }
}

@Composable
private fun ServerCard(server: Server) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(server.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(server.outbound.protocolLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(server.outbound.hostPort(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Outbound.protocolLabel(): String = when (this) {
    is Outbound.Vless -> "VLESS"
    is Outbound.Vmess -> "VMess"
    is Outbound.Trojan -> "Trojan"
    is Outbound.Shadowsocks -> "Shadowsocks"
}

private fun Outbound.hostPort(): String = "$host:$port"
