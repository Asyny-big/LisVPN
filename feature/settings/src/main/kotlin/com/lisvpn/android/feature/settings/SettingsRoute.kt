package com.lisvpn.android.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.lisvpn.android.core.common.logging.LisLogExporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

const val SETTINGS_ROUTE = "settings"

fun NavGraphBuilder.settingsRoute(onBack: () -> Unit) {
    composable(SETTINGS_ROUTE) { SettingsRoute(onBack = onBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val exporter = EntryPointAccessors.fromApplication(context.applicationContext, SettingsEntryPoint::class.java).logExporter()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Диагностика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Экспортируйте логи VPN, libbox, импорта подписки и переподключений для первого физического теста.")
                    TextButton(onClick = { shareLogs(context, exporter.readText()) }) { Text("Экспортировать логи") }
                    TextButton(onClick = exporter::clear) { Text("Очистить логи") }
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MVP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Backend: govchat.ru для dev-сборки")
                    Text("Импорт: subscription URL и VLESS URI")
                }
            }
        }
    }
}

private fun shareLogs(context: android.content.Context, logs: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "LisVPN logs")
        putExtra(Intent.EXTRA_TEXT, logs)
    }
    context.startActivity(Intent.createChooser(intent, "Экспорт логов LisVPN").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun logExporter(): LisLogExporter
}
