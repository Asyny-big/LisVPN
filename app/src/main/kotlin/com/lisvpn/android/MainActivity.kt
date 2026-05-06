package com.lisvpn.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lisvpn.android.core.designsystem.theme.LisTheme
import com.lisvpn.android.deeplink.DeepLinkParser
import com.lisvpn.android.deeplink.DeepLinkResult
import com.lisvpn.android.navigation.LisNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * Single-Activity host for the entire UI.
 *
 * Responsibilities:
 *  - Compose entry-point + edge-to-edge configuration.
 *  - Splash screen handover.
 *  - Deep link interception (lisvpn://import?sub=... and https://lisvpn.ru/c/<token>).
 *
 * State is intentionally minimal — everything else lives in feature ViewModels exposed via Hilt.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinkParser: DeepLinkParser
    private var pendingDeepLink by mutableStateOf<DeepLinkResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )

        // Splash stays until the first frame; we don't gate on async init for now.
        splash.setKeepOnScreenCondition { false }

        pendingDeepLink = parseDeepLink(intent)
        Timber.d("MainActivity onCreate, initialDeepLink=%s", pendingDeepLink)

        setContent {
            LisTheme {
                LisNavHost(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent)?.let { pendingDeepLink = it }
    }

    private fun parseDeepLink(intent: Intent?): DeepLinkResult? =
        intent?.data?.let(deepLinkParser::parse)?.also { Timber.d("DeepLink resolved: %s", it) }
}
