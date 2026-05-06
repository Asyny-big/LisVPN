package com.lisvpn.android.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lisvpn.android.core.designsystem.component.LisPrimaryButton
import com.lisvpn.android.core.domain.model.Profile
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val PROFILES_ROUTE = "profiles"
const val IMPORT_ROUTE = "profiles/import"
private const val IMPORT_WITH_URL_ROUTE = "profiles/import?url={url}"

fun profilesRoute(url: String? = null): String = if (url.isNullOrBlank()) {
    IMPORT_ROUTE
} else {
    "profiles/import?url=${android.net.Uri.encode(url)}"
}

fun NavGraphBuilder.profilesGraph(
    onBack: () -> Unit,
    onOpenImport: (String?) -> Unit,
) {
    composable(PROFILES_ROUTE) {
        ProfilesRoute(onBack = onBack, onOpenImport = onOpenImport)
    }
    composable(IMPORT_ROUTE) {
        ImportProfileRoute(initialUrl = null, onBack = onBack)
    }
    composable(
        route = IMPORT_WITH_URL_ROUTE,
        arguments = listOf(navArgument("url") { defaultValue = "" }),
    ) { entry ->
        ImportProfileRoute(
            initialUrl = entry.arguments?.getString("url"),
            onBack = onBack,
        )
    }
}

@Composable
private fun ProfilesRoute(
    onBack: () -> Unit,
    onOpenImport: (String?) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfilesScreen(
        state = state,
        onBack = onBack,
        onImport = { onOpenImport(null) },
        onSetActive = viewModel::setActive,
        onRefresh = viewModel::refresh,
        onDelete = viewModel::delete,
        onMessageShown = viewModel::consumeMessage,
    )
}

@Composable
private fun ImportProfileRoute(
    initialUrl: String?,
    onBack: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl.orEmpty()) }
    var autoImportedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank() && autoImportedUrl != initialUrl) {
            autoImportedUrl = initialUrl
            url = initialUrl
            viewModel.importSubscription(initialUrl)
        }
    }
    ImportProfileScreen(
        url = url,
        state = state,
        onUrlChange = { url = it },
        onImport = { viewModel.importSubscription(url) },
        onBack = onBack,
        onMessageShown = viewModel::consumeMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesScreen(
    state: ProfilesUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSetActive: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        onMessageShown()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профили") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") } },
                actions = { TextButton(onClick = onImport) { Text("Импорт") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.profiles.isEmpty()) {
                item {
                    EmptyProfilesCard(onImport = onImport)
                }
            }
            items(state.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    serverCount = if (profile.isPrimary) state.activeServers.size else null,
                    onSetActive = { onSetActive(profile.id) },
                    onRefresh = { onRefresh(profile.id) },
                    onDelete = { onDelete(profile.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProfileScreen(
    url: String,
    state: ProfilesUiState,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        onMessageShown()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Импорт подписки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Вставьте ссылку подписки LisVPN или VLESS URI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("Subscription URL") },
                placeholder = { Text("https://govchat.ru/sub/...") },
                enabled = !state.importing,
            )
            LisPrimaryButton(
                text = "Импортировать",
                onClick = onImport,
                enabled = url.isNotBlank(),
                loading = state.importing,
            )
        }
    }
}

@Composable
private fun EmptyProfilesCard(onImport: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Нет активной подписки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Импортируйте ссылку из Telegram-бота LisVPN, чтобы подключиться к VPN.")
            LisPrimaryButton(text = "Импортировать подписку", onClick = onImport)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    serverCount: Int?,
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.expiresAt?.let { "До ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}" } ?: "Без срока", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (profile.isPrimary) Icon(Icons.Outlined.CheckCircle, contentDescription = "Активен", tint = MaterialTheme.colorScheme.primary)
            }
            profile.announceMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            serverCount?.let { Text("Серверов: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSetActive, enabled = !profile.isPrimary) { Text("Выбрать") }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Удалить") }
            }
        }
    }
}
