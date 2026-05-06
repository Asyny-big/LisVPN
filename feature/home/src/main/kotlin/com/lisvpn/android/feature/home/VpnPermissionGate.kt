package com.lisvpn.android.feature.home

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.lisvpn.android.core.domain.repository.VpnPermissionHandle
import com.lisvpn.android.vpn.core.VpnConnectionController
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges Android's VpnService permission flow (`VpnService.prepare()` + Activity result) to the
 * suspend-based [VpnPermissionHandle] domain interface.
 *
 * Returns a stable [VpnPermissionHandle] that can be passed into use cases. The launcher is
 * scoped to the calling Composable; pending requests are completed when the Activity result
 * arrives.
 */
@Composable
internal fun rememberVpnPermissionHandle(): VpnPermissionHandle {
    val context = LocalContext.current
    val controller = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            HomeFeatureEntryPoint::class.java,
        ).vpnConnectionController()
    }

    val pendingRef = remember { PendingRef() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pendingRef.deferred?.complete(result.resultCode == Activity.RESULT_OK)
        pendingRef.deferred = null
    }

    return remember(launcher, controller) {
        object : VpnPermissionHandle {
            override suspend fun ensureGranted(): Boolean {
                val intent: Intent = controller.preparePermissionIntent() ?: return true
                val deferred = CompletableDeferred<Boolean>()
                pendingRef.deferred = deferred
                launcher.launch(intent)
                return deferred.await()
            }
        }
    }
}

private class PendingRef {
    var deferred: CompletableDeferred<Boolean>? = null
}

/**
 * Hilt EntryPoint that lets a Composable retrieve the singleton [VpnConnectionController]
 * without requiring the Activity to be a Hilt entry point of its own (it already is — but this
 * keeps the helper self-contained and reusable from non-Activity contexts).
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface HomeFeatureEntryPoint {
    fun vpnConnectionController(): VpnConnectionController
}
