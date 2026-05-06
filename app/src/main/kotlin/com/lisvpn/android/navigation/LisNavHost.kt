package com.lisvpn.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lisvpn.android.deeplink.DeepLinkResult
import com.lisvpn.android.feature.home.homeRoute
import com.lisvpn.android.feature.profiles.profilesGraph
import com.lisvpn.android.feature.profiles.profilesRoute
import com.lisvpn.android.feature.servers.serversRoute
import com.lisvpn.android.feature.settings.settingsRoute

/**
 * Top-level navigation graph. Routes are declared as constants in [LisRoute] and consumed by feature modules
 * that expose extension functions on [androidx.navigation.NavGraphBuilder] (see [homeRoute]).
 */
@Composable
fun LisNavHost(
    pendingDeepLink: DeepLinkResult?,
    onDeepLinkConsumed: () -> Unit,
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingDeepLink) {
        when (val link = pendingDeepLink) {
            is DeepLinkResult.ImportSubscription -> {
                navController.navigate(profilesRoute(link.subscriptionUrl)) {
                    launchSingleTop = true
                }
                onDeepLinkConsumed()
            }
            null -> Unit
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box {
            NavHost(
                navController = navController,
                startDestination = LisRoute.Home,
            ) {
                homeRoute(
                    onNavigateToServers = { navController.navigate(LisRoute.Servers) },
                    onNavigateToSettings = { navController.navigate(LisRoute.Settings) },
                    onNavigateToProfiles = { navController.navigate(LisRoute.Profiles) },
                    onNavigateToImport = { navController.navigate(profilesRoute()) },
                )

                serversRoute(onBack = { navController.popBackStack() })
                settingsRoute(onBack = { navController.popBackStack() })
                profilesGraph(
                    onBack = { navController.popBackStack() },
                    onOpenImport = { url -> navController.navigate(profilesRoute(url)) },
                )
                composable(LisRoute.SplitTunnel) { /* feature/splittunnel placeholder */ }
            }
        }
    }
}
