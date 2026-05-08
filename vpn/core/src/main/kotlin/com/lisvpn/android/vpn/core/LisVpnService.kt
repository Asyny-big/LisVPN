package com.lisvpn.android.vpn.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.ConnectedServer
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.vpn.libbox.LibboxBridge
import java.io.File
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import timber.log.Timber

@AndroidEntryPoint
class LisVpnService : VpnService() {

    @Inject lateinit var controller: VpnConnectionController
    @Inject lateinit var notifier: VpnNotifier
    @Inject lateinit var appRulesRepository: AppRulesRepository
    @Inject lateinit var startContext: VpnStartContext
    @Inject lateinit var autoOptimizerRepository: AutoOptimizerRepository
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private var serviceScope: CoroutineScope? = null
    private var bridge: LibboxBridge? = null
    private var startJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeServer: ConnectedServer? = null
    private var connectedAt: Instant? = null
    private var reconnectAttempt: Int = 0
    private var reconnectWakeJob: Job? = null
    private var sleepingForNetwork: Boolean = false

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        Timber.d("LisVpnService.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Timber.w("LisVpnService restarted without config")
            controller.publishIdle()
            stopSelf()
            return START_STICKY
        }
        when (intent.action) {
            VpnIntents.ACTION_START -> handleStart(intent)
            VpnIntents.ACTION_STOP -> handleStop()
            VpnIntents.ACTION_RECONNECT -> handleReconnect()
            VpnIntents.ACTION_SELECT_OUTBOUND -> handleSelectOutbound(intent)
            else -> Timber.w("LisVpnService: unknown action %s", intent.action)
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        Timber.i("LisVpnService.handleStart")
        val configJson = intent.getStringExtra(VpnIntents.EXTRA_CONFIG_JSON)
        if (configJson.isNullOrBlank()) {
            controller.publishError(VpnState.Reason.ConfigInvalid, "Empty config")
            stopSelfCleanly()
            return
        }
        val serverLabel = intent.getStringExtra(VpnIntents.EXTRA_SERVER_LABEL)
        val pending = startContext.consume()

        promoteForeground(VpnState.Connecting(serverLabel))

        val previousStartJob = startJob
        previousStartJob?.cancel()
        startJob = serviceScope?.launch {
            previousStartJob?.cancelAndJoin()
            resetRuntime()
            if (!hasUsableNetwork()) {
                Timber.w("VPN start aborted: no usable non-VPN network")
                controller.publishError(VpnState.Reason.NetworkUnavailable, "No validated network")
                stopSelfCleanly()
                return@launch
            }
            val rules = pending?.appRules
                ?: runCatching { appRulesRepository.observe().first() }.getOrDefault(AppRules.Default)

            // ----------------------------------------------------------
            // AUTO mode preflight (pre-VPN speed test) — runs only when
            // we have a multi-candidate AUTO start. Libbox is brought up in
            // headless / SOCKS-only mode (no TUN), so the user's apps keep
            // using the regular network while we measure each candidate.
            // ----------------------------------------------------------
            var winnerOutboundTag: String? = null
            val preflightConfigJson = pending?.preflightConfigJson
            val preflightCandidates = pending?.candidates.orEmpty()
            val shouldRunPreflight =
                pending != null &&
                    pending.smartSelection &&
                    !preflightConfigJson.isNullOrBlank() &&
                    preflightCandidates.size > 1
            if (shouldRunPreflight) {
                val preflightBridge = LibboxBridge(
                    service = this@LisVpnService,
                    configJson = preflightConfigJson!!,
                    appRules = rules,
                )
                bridge = preflightBridge
                Timber.i("Preflight libbox: validate")
                val preflightValidation = preflightBridge.validate()
                if (preflightValidation.isFailure) {
                    Timber.e(preflightValidation.exceptionOrNull(), "Preflight config validation failed; skipping preflight")
                } else {
                    Timber.i("Preflight libbox: start")
                    val preflightStart = preflightBridge.start()
                    if (preflightStart.isFailure) {
                        Timber.e(preflightStart.exceptionOrNull(), "Preflight libbox start failed; skipping preflight")
                    } else {
                        val preflight = runCatching {
                            autoOptimizerRepository.runPreflight(preflightCandidates)
                        }
                        preflight
                            .onSuccess { result ->
                                winnerOutboundTag = result.winnerOutboundTag
                                Timber.i(
                                    "Preflight finished: winnerTag=%s tested=%d speedKbps=%s",
                                    result.winnerOutboundTag,
                                    result.tested,
                                    result.winnerSpeedKbps,
                                )
                            }
                            .onFailure { Timber.e(it, "Preflight runPreflight() failed") }
                    }
                }
                Timber.i("Preflight libbox: stop")
                preflightBridge.stop()
                bridge = null
            }

            // ----------------------------------------------------------
            // Real VPN tunnel start
            // ----------------------------------------------------------
            val newBridge = LibboxBridge(service = this@LisVpnService, configJson = configJson, appRules = rules)
            bridge = newBridge
            Timber.i("libbox config validation requested")
            val validation = newBridge.validate()
            if (validation.isFailure) {
                val err = validation.exceptionOrNull()
                Timber.e(err, "libbox config validation failed")
                bridge = null
                controller.publishError(VpnState.Reason.ConfigInvalid, err?.message)
                stopSelfCleanly()
                return@launch
            }
            Timber.i("libbox start requested")
            val startResult = newBridge.start()
            if (startResult.isFailure) {
                val err = startResult.exceptionOrNull()
                Timber.e(err, "libbox start failed")
                unregisterNetworkCallback()
                bridge = null
                controller.publishError(VpnState.Reason.StartFailed, err?.message)
                stopSelfCleanly()
                return@launch
            }
            // Apply the preflight winner to the real tunnel's `auto` selector. The selector
            // group is created with `outbounds.first()` as the default — we override it here
            // so that the connected user actually goes through the candidate the preflight
            // measured to be fastest, not just the first server in the bootstrap-ranked list.
            val winnerTag = winnerOutboundTag
            if (winnerTag != null) {
                Timber.i("Applying preflight winner to auto selector: outbound=%s", winnerTag)
                newBridge.selectOutbound(AUTO_SELECTOR_TAG, winnerTag)
                    .onFailure { Timber.w(it, "Failed to apply preflight winner to auto selector") }
            }
            val cs = ConnectedServer(
                serverId = serverLabel.orEmpty(),
                displayName = winnerDisplayNameOrFallback(winnerTag, pending) ?: serverLabel ?: "Авто",
                countryCode = null,
            )
            val now = Clock.System.now()
            activeServer = cs
            connectedAt = now
            reconnectAttempt = 0
            registerNetworkCallback()
            controller.publishConnected(cs, now)
            updateNotification(VpnState.Connected(cs, now))
        }
    }

    private fun winnerDisplayNameOrFallback(
        winnerTag: String?,
        pending: VpnStartContext.Pending?,
    ): String? {
        if (winnerTag == null || pending == null) return null
        val index = winnerTag.removePrefix("srv-").toIntOrNull() ?: return null
        return pending.candidates.getOrNull(index)?.displayName
    }

    private fun handleStop() {
        Timber.i("LisVpnService.handleStop")
        val stoppingStartJob = startJob
        stoppingStartJob?.cancel()
        serviceScope?.launch {
            stoppingStartJob?.cancelAndJoin()
            resetRuntime()
            startJob = null
            controller.publishIdle()
            stopSelfCleanly()
        }
    }

    private fun handleReconnect() {
        serviceScope?.launch {
            val runningBridge = bridge
            if (runningBridge == null || !runningBridge.isRunning()) {
                controller.publishError(VpnState.Reason.StartFailed, "VPN engine is not running")
                stopSelfCleanly()
                return@launch
            }
            reconnectAttempt += 1
            val reconnecting = VpnState.Reconnecting(reconnectAttempt, activeServer?.displayName)
            Timber.i("Manual VPN reconnect requested: attempt=%d", reconnectAttempt)
            reconnectWakeJob?.cancel()
            sleepingForNetwork = false
            controller.publishReconnecting(reconnectAttempt)
            updateNotification(reconnecting)
            runningBridge.sleep()
            delay(RECONNECT_WAKE_DELAY_MS)
            runningBridge.wake()
                .onSuccess { publishConnectedAgain() }
                .onFailure { err ->
                    Timber.e(err, "libbox manual wake failed")
                    controller.publishError(VpnState.Reason.StartFailed, err.message)
                }
        }
    }

    private fun handleSelectOutbound(intent: Intent) {
        val groupTag = intent.getStringExtra(VpnIntents.EXTRA_OUTBOUND_GROUP).orEmpty()
        val outboundTag = intent.getStringExtra(VpnIntents.EXTRA_OUTBOUND_TAG).orEmpty()
        if (groupTag.isBlank() || outboundTag.isBlank()) {
            Timber.w("Outbound switch ignored: empty group=%s outbound=%s", groupTag, outboundTag)
            return
        }
        serviceScope?.launch {
            val runningBridge = bridge
            if (runningBridge == null || !runningBridge.isRunning()) {
                Timber.w("Outbound switch ignored: libbox is not running group=%s outbound=%s", groupTag, outboundTag)
                return@launch
            }
            runningBridge.selectOutbound(groupTag, outboundTag)
                .onSuccess { Timber.i("Outbound switch applied: group=%s outbound=%s", groupTag, outboundTag) }
                .onFailure { Timber.e(it, "Outbound switch failed: group=%s outbound=%s", groupTag, outboundTag) }
        }
    }

    override fun onRevoke() {
        Timber.w("VpnService permission revoked by OS/user")
        controller.publishError(VpnState.Reason.PermissionRevoked)
        val revokedStartJob = startJob
        revokedStartJob?.cancel()
        serviceScope?.launch {
            revokedStartJob?.cancelAndJoin()
            resetRuntime()
            startJob = null
            stopSelfCleanly()
        }
    }

    override fun onDestroy() {
        Timber.d("LisVpnService.onDestroy")
        startJob?.cancel()
        reconnectWakeJob?.cancel()
        unregisterNetworkCallback()
        runCatching { kotlinx.coroutines.runBlocking { bridge?.stop() } }
        bridge = null
        startJob = null
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                serviceScope?.launch {
                    if (isVpnNetwork(network)) return@launch
                    val runningBridge = bridge
                    if (runningBridge == null || !runningBridge.isRunning()) return@launch
                    if (!hasUsableNetwork()) {
                        suspendForNetwork(runningBridge, "Default network lost")
                    }
                }
            }

            override fun onAvailable(network: Network) {
                serviceScope?.launch {
                    if (isVpnNetwork(network)) return@launch
                    val runningBridge = bridge
                    if (runningBridge == null || !runningBridge.isRunning()) return@launch
                    scheduleNetworkWake(runningBridge)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                serviceScope?.launch {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@launch
                    val runningBridge = bridge ?: return@launch
                    if (!runningBridge.isRunning()) return@launch
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        scheduleNetworkWake(runningBridge)
                    } else if (!hasUsableNetwork()) {
                        suspendForNetwork(runningBridge, "Network lost INTERNET capability")
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Timber.i("ConnectivityManager callback registered")
        }.onFailure { Timber.e(it, "Failed to register network callback") }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            .onFailure { Timber.w(it, "Failed to unregister network callback") }
        networkCallback = null
    }

    private fun publishConnectedAgain() {
        val server = activeServer ?: return
        val at = connectedAt ?: Clock.System.now().also { connectedAt = it }
        reconnectAttempt = 0
        sleepingForNetwork = false
        controller.publishConnected(server, at)
        updateNotification(VpnState.Connected(server, at))
    }

    private suspend fun resetRuntime() {
        unregisterNetworkCallback()
        reconnectWakeJob?.cancelAndJoin()
        reconnectWakeJob = null
        bridge?.stop()
        bridge = null
        activeServer = null
        connectedAt = null
        reconnectAttempt = 0
        sleepingForNetwork = false
    }

    private suspend fun suspendForNetwork(runningBridge: LibboxBridge, reason: String) {
        if (sleepingForNetwork) return
        reconnectWakeJob?.cancel()
        reconnectWakeJob = null
        sleepingForNetwork = true
        reconnectAttempt += 1
        Timber.w("%s: attempt=%d", reason, reconnectAttempt)
        runningBridge.sleep()
        val state = VpnState.Reconnecting(reconnectAttempt, activeServer?.displayName)
        controller.publishReconnecting(reconnectAttempt)
        updateNotification(state)
    }

    private fun scheduleNetworkWake(runningBridge: LibboxBridge) {
        if (!sleepingForNetwork) return
        reconnectWakeJob?.cancel()
        val delayMs = reconnectDelayMs(reconnectAttempt)
        reconnectWakeJob = serviceScope?.launch {
            Timber.i("Default network available, wake delayed by %d ms", delayMs)
            delay(delayMs)
            if (!hasUsableNetwork()) return@launch
            runningBridge.wake()
                .onSuccess { publishConnectedAgain() }
                .onFailure { Timber.e(it, "libbox wake failed") }
        }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, RECONNECT_MAX_EXPONENT)
        val base = RECONNECT_BASE_DELAY_MS * (1L shl exponent)
        return base.coerceAtMost(RECONNECT_MAX_DELAY_MS) + Random.nextLong(RECONNECT_JITTER_MS)
    }

    private fun hasUsableNetwork(): Boolean {
        return connectivityManager.allNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun isVpnNetwork(network: Network): Boolean =
        connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun promoteForeground(state: VpnState) {
        val notification = notifier.build(state = state, openAppPendingIntent = openAppPendingIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VpnNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(VpnNotifier.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: VpnState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(VpnNotifier.NOTIFICATION_ID, notifier.build(state = state, openAppPendingIntent = openAppPendingIntent()))
    }

    private fun stopSelfCleanly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun openAppPendingIntent(): PendingIntent? = runCatching {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching null
        PendingIntent.getActivity(
            this,
            REQ_OPEN,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private val connectivityManager: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private companion object {
        const val REQ_OPEN = 21
        const val RECONNECT_WAKE_DELAY_MS = 250L
        const val RECONNECT_BASE_DELAY_MS = 1_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val RECONNECT_JITTER_MS = 1_500L
        const val RECONNECT_MAX_EXPONENT = 5
        // Mirrors com.lisvpn.android.vpn.config.SingBoxConfigBuilder.AUTO_TAG. Kept private over
        // there to keep the builder's surface tight; here it's just the wire string we hand back
        // to libbox via selectOutbound().
        const val AUTO_SELECTOR_TAG = "auto"
    }
}
