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
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerStatus
import com.lisvpn.android.vpn.libbox.LibboxBridge
import com.lisvpn.android.vpn.health.AutoFailoverManager
import com.lisvpn.android.vpn.health.AutoFailoverSwitchController
import com.lisvpn.android.vpn.health.AutoSelectionCandidate
import com.lisvpn.android.vpn.health.FastProbeResult
import com.lisvpn.android.vpn.health.FastProbeWorker
import com.lisvpn.android.vpn.health.ScoreCalculator
import com.lisvpn.android.vpn.health.ScoredAutoServer
import com.lisvpn.android.vpn.health.SmartNetworkProfile
import com.lisvpn.android.vpn.health.SmartServerCache
import com.lisvpn.android.vpn.health.TaggedServer
import com.lisvpn.android.vpn.health.ThroughputWorker
import com.lisvpn.android.vpn.health.TunnelValidationWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    @Inject lateinit var fastProbeWorker: FastProbeWorker
    @Inject lateinit var tunnelValidationWorker: TunnelValidationWorker
    @Inject lateinit var throughputWorker: ThroughputWorker
    @Inject lateinit var scoreCalculator: ScoreCalculator
    @Inject lateinit var smartServerCache: SmartServerCache
    @Inject lateinit var autoFailoverManager: AutoFailoverManager
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private var serviceScope: CoroutineScope? = null
    private var bridge: LibboxBridge? = null
    private var startJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val reconnectMutex = Mutex()
    private var activeServer: ConnectedServer? = null
    private var connectedAt: Instant? = null
    private var reconnectAttempt: Int = 0
    private var reconnectWakeJob: Job? = null
    private var failoverJob: Job? = null
    private var sleepingForNetwork: Boolean = false
    // Set when manual-mode tunnel validation finishes without proving the tunnel carries
    // HTTP traffic. We still keep the tunnel up because the user explicitly picked the server,
    // but we surface this in the foreground notification so "VPN connected but no internet"
    // does not look identical to a healthy session.
    private var manualValidationWarning: String? = null

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

            val autoCandidates = pending?.candidates.orEmpty()
            val shouldRunAutoValidation = pending?.smartSelection == true && autoCandidates.size > 1
            val autoPlan = if (shouldRunAutoValidation) {
                try {
                    buildAutoSelectionPlan(autoCandidates)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.e(e, "AUTO fast filter crashed")
                    null
                }
            } else {
                null
            }
            if (shouldRunAutoValidation && autoPlan == null) {
                controller.publishError(VpnState.Reason.NetworkUnavailable, "No reachable AUTO candidates")
                stopSelfCleanly()
                return@launch
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
            val manualCandidate = pending
                ?.takeIf { !it.smartSelection }
                ?.candidates
                ?.singleOrNull()
            // Register the network callback BEFORE running tunnel validation. The callback is
            // what suspends/wakes libbox when the underlying network flips (Wi-Fi <-> cellular).
            // If validation runs first and the underlying network drops during those ~7 seconds,
            // we miss the onLost callback and the tunnel comes up dead. Registering early is
            // idempotent (registerNetworkCallback() bails if already set) and harmless even if
            // the user later cancels the start.
            if (manualCandidate != null) {
                registerNetworkCallback()
            }
            if (manualCandidate != null && !validateManualTunnel(newBridge, manualCandidate)) {
                bridge = null
                stopSelfCleanly()
                return@launch
            }
            val autoResult = if (autoPlan != null) {
                try {
                    runAutoTunnelValidation(newBridge, autoPlan)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.e(e, "AUTO tunnel validation crashed")
                    null
                }
            } else {
                null
            }
            if (autoPlan != null && autoResult == null) {
                Timber.w("AUTO start aborted: no candidate passed real tunnel validation")
                newBridge.stop()
                bridge = null
                controller.publishError(VpnState.Reason.StartFailed, "No AUTO server has working internet on this network")
                stopSelfCleanly()
                return@launch
            }
            val selectedServer = autoResult?.best?.server ?: pending?.candidates?.firstOrNull()
            val cs = ConnectedServer(
                serverId = selectedServer?.id ?: serverLabel.orEmpty(),
                displayName = selectedServer?.displayName ?: serverLabel ?: "Авто",
                countryCode = selectedServer?.countryCode,
            )
            val now = Clock.System.now()
            activeServer = cs
            connectedAt = now
            reconnectAttempt = 0
            registerNetworkCallback()
            controller.publishConnected(cs, now)
            updateNotification(VpnState.Connected(cs, now))
            autoResult?.let { startAutoFailover(it) }
        }
    }

    private suspend fun buildAutoSelectionPlan(candidates: List<Server>): AutoSelectionPlan? {
        val profile = smartServerCache.currentProfile()
        val tagged = candidates.mapIndexed { index, server -> TaggedServer(server, "srv-$index") }
        autoOptimizerRepository.report(
            AutoOptimizerStatus.Probing(
                current = 1,
                total = tagged.size,
                serverDisplayName = "Быстрый фильтр всех серверов",
            ),
        )
        val histories = smartServerCache.histories(profile, tagged.map { it.server.id })
        val fastResults = fastProbeWorker.probeAll(tagged)
        val shortlist = scoreCalculator.shortlist(
            fastResults = fastResults,
            histories = histories,
            profile = profile,
            limit = AUTO_SHORTLIST_LIMIT,
        )
        Timber.i(
            "AUTO stage1 fast filter: network=%s fingerprint=%s total=%d reachable=%d shortlist=%s failures=%s",
            profile.networkClass,
            profile.fingerprint,
            tagged.size,
            fastResults.count { it.success },
            shortlist.joinToString { "${it.server.displayName}/${it.fastProbe.latencyMs}ms" },
            fastResults.filterNot { it.success }.take(6).joinToString { "${it.taggedServer.server.displayName}:${it.failureReason}" },
        )
        if (shortlist.isEmpty()) {
            autoOptimizerRepository.report(AutoOptimizerStatus.Failed("no reachable servers"))
            return null
        }
        return AutoSelectionPlan(profile = profile, shortlist = shortlist, fastResults = fastResults)
    }

    private suspend fun runAutoTunnelValidation(
        runningBridge: LibboxBridge,
        plan: AutoSelectionPlan,
    ): AutoSelectionResult? {
        val validationCandidates = plan.shortlist.take(AUTO_TUNNEL_VALIDATION_LIMIT)
        val scored = mutableListOf<ScoredAutoServer>()
        var lastSuccessful: ScoredAutoServer? = null
        for ((index, candidate) in validationCandidates.withIndex()) {
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Probing(
                    current = index + 1,
                    total = validationCandidates.size,
                    serverDisplayName = candidate.server.displayName,
                    lastSpeedKbps = lastSuccessful?.throughput?.kbps,
                    lastServerDisplayName = lastSuccessful?.server?.displayName,
                ),
            )
            val switched = runningBridge.selectOutbound(AUTO_SELECTOR_TAG, candidate.outboundTag)
            if (switched.isFailure) {
                val failedValidation = com.lisvpn.android.vpn.health.TunnelValidationResult.failed(
                    serverId = candidate.server.id,
                    reason = "selector switch failed",
                )
                val failed = ScoredAutoServer(
                    candidate = candidate,
                    validation = failedValidation,
                    throughput = null,
                    score = scoreCalculator.score(candidate, failedValidation, null, plan.profile),
                )
                scored += failed
                smartServerCache.record(plan.profile, failed)
                Timber.w(switched.exceptionOrNull(), "AUTO candidate skipped: selector switch failed server=%s", candidate.server.displayName)
                continue
            }
            delay(if (index == 0) FIRST_SELECTOR_SWITCH_WARMUP_MS else SELECTOR_SWITCH_WARMUP_MS)
            val validation = tunnelValidationWorker.validate(candidate.server.id)
            val throughput = if (validation.eligible) throughputWorker.measure() else null
            val score = scoreCalculator.score(candidate, validation, throughput, plan.profile)
            val current = ScoredAutoServer(
                candidate = candidate,
                validation = validation,
                throughput = throughput,
                score = score,
            )
            scored += current
            smartServerCache.record(plan.profile, current)
            if (validation.eligible) lastSuccessful = current
            Timber.i(
                "AUTO stage2/3 candidate: server=%s tag=%s eligible=%s score=%d speedKbps=%s http=%d/%d rtt=%sms jitter=%sms loss=%.2f reason=%s",
                candidate.server.displayName,
                candidate.outboundTag,
                validation.eligible,
                scoreCalculator.diagnosticScore(score),
                throughput?.kbps,
                validation.successCount,
                validation.checkCount,
                validation.averageRttMs,
                validation.jitterMs,
                validation.packetLossApprox,
                validation.failureReason,
            )
            val bestSoFar = lastSuccessful
            if (bestSoFar != null && shouldStopAutoValidation(scored.size, bestSoFar)) {
                Timber.i(
                    "AUTO validation stopped early: best=%s tested=%d speedKbps=%s",
                    bestSoFar.server.displayName,
                    scored.size,
                    bestSoFar.throughput?.kbps,
                )
                break
            }
        }

        val validated = scored
            .filter { it.eligible }
            .sortedWith(
                compareByDescending<ScoredAutoServer> { it.score }
                    .thenByDescending { it.throughput?.mbps ?: 0.0 }
                    .thenBy { it.validation.averageRttMs ?: Int.MAX_VALUE },
            )
        val best = validated.firstOrNull()
        if (best == null) {
            val fallback = scored.firstOrNull()
                ?: validationCandidates.firstOrNull()?.let { candidate ->
                    val validation = com.lisvpn.android.vpn.health.TunnelValidationResult.failed(
                        serverId = candidate.server.id,
                        reason = "not validated",
                    )
                    ScoredAutoServer(
                        candidate = candidate,
                        validation = validation,
                        throughput = null,
                        score = scoreCalculator.score(candidate, validation, null, plan.profile),
                    )
                }
                ?: return null
            runningBridge.selectOutbound(AUTO_SELECTOR_TAG, fallback.outboundTag)
                .onFailure { Timber.w(it, "Failed to switch AUTO selector to fallback server") }
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Done(
                    bestServerDisplayName = fallback.server.displayName,
                    bestSpeedKbps = null,
                    tested = scored.size,
                ),
            )
            Timber.w(
                "AUTO selection fallback: no validated server, continuing with bootstrap server=%s tag=%s tested=%d",
                fallback.server.displayName,
                fallback.outboundTag,
                scored.size,
            )
            return AutoSelectionResult(profile = plan.profile, best = fallback, validated = emptyList())
        }
        runningBridge.selectOutbound(AUTO_SELECTOR_TAG, best.outboundTag)
            .onFailure { Timber.w(it, "Failed to switch AUTO selector to validated winner") }
        autoOptimizerRepository.report(
            AutoOptimizerStatus.Done(
                bestServerDisplayName = best.server.displayName,
                bestSpeedKbps = best.throughput?.kbps,
                tested = scored.size,
            ),
        )
        Timber.i(
            "AUTO selection finished: best=%s tag=%s score=%d validated=%d tested=%d speedKbps=%s",
            best.server.displayName,
            best.outboundTag,
            scoreCalculator.diagnosticScore(best.score),
            validated.size,
            scored.size,
            best.throughput?.kbps,
        )
        return AutoSelectionResult(profile = plan.profile, best = best, validated = validated)
    }

    private fun shouldStopAutoValidation(tested: Int, currentBest: ScoredAutoServer): Boolean {
        val speedKbps = currentBest.throughput?.kbps ?: 0L
        return speedKbps >= AUTO_GOOD_ENOUGH_SPEED_KBPS || tested >= AUTO_MIN_TESTED_AFTER_SUCCESS
    }

    private suspend fun validateManualTunnel(
        @Suppress("UNUSED_PARAMETER") runningBridge: LibboxBridge,
        server: Server,
    ): Boolean {
        delay(MANUAL_VALIDATION_WARMUP_MS)
        val validation = tunnelValidationWorker.validateManual(server.id)
        Timber.i(
            "Manual tunnel validation: server=%s eligible=%s dns=%s http=%d/%d rtt=%sms reason=%s",
            server.displayName,
            validation.eligible,
            validation.dnsWorks,
            validation.successCount,
            validation.checkCount,
            validation.averageRttMs,
            validation.failureReason,
        )
        if (validation.eligible) {
            manualValidationWarning = null
            return true
        }

        manualValidationWarning = buildManualValidationWarning(validation)
        Timber.w(
            "Manual tunnel validation failed, continuing because the user explicitly selected this server: server=%s reason=%s",
            server.displayName,
            validation.failureReason,
        )
        return true
    }

    private fun buildManualValidationWarning(
        validation: com.lisvpn.android.vpn.health.TunnelValidationResult,
    ): String {
        if (!validation.dnsWorks) return "DNS не работает через тоннель"
        val reason = validation.failureReason
        return if (!reason.isNullOrBlank()) "тоннель не пропускает HTTP ($reason)"
        else "тоннель не пропускает HTTP"
    }

    private fun startAutoFailover(result: AutoSelectionResult) {
        failoverJob?.cancel()
        val scope = serviceScope ?: return
        failoverJob = autoFailoverManager.start(
            scope = scope,
            profile = result.profile,
            validatedServers = result.validated,
            initialOutboundTag = result.best.outboundTag,
            switchController = object : AutoFailoverSwitchController {
                override suspend fun switchTo(outboundTag: String): Boolean = reconnectMutex.withLock {
                    val runningBridge = bridge ?: return@withLock false
                    runningBridge.selectOutbound(AUTO_SELECTOR_TAG, outboundTag).isSuccess
                }

                override suspend fun onFailoverStarted(attempt: Int, fromServerDisplayName: String?) {
                    reconnectAttempt = attempt
                    val state = VpnState.Reconnecting(attempt, fromServerDisplayName)
                    controller.publishReconnecting(attempt)
                    updateNotification(state)
                }

                override suspend fun onFailoverSucceeded(
                    server: com.lisvpn.android.core.domain.model.Server,
                    outboundTag: String,
                ) {
                    val connectedServer = ConnectedServer(
                        serverId = server.id,
                        displayName = server.displayName,
                        countryCode = server.countryCode,
                    )
                    activeServer = connectedServer
                    val now = Clock.System.now()
                    connectedAt = now
                    reconnectAttempt = 0
                    sleepingForNetwork = false
                    controller.publishConnected(connectedServer, now)
                    updateNotification(VpnState.Connected(connectedServer, now))
                    Timber.i("AUTO failover published connected: server=%s tag=%s", server.displayName, outboundTag)
                }

                override suspend fun onFailoverExhausted(reason: String) {
                    Timber.w("AUTO failover exhausted: %s", reason)
                    controller.publishError(VpnState.Reason.NetworkUnavailable, reason)
                    updateNotification(VpnState.Error(VpnState.Reason.NetworkUnavailable, reason))
                }
            },
        )
    }

    private data class AutoSelectionPlan(
        val profile: SmartNetworkProfile,
        val shortlist: List<AutoSelectionCandidate>,
        val fastResults: List<FastProbeResult>,
    )

    private data class AutoSelectionResult(
        val profile: SmartNetworkProfile,
        val best: ScoredAutoServer,
        val validated: List<ScoredAutoServer>,
    )

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
            reconnectMutex.withLock {
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
    }

    private fun handleSelectOutbound(intent: Intent) {
        val groupTag = intent.getStringExtra(VpnIntents.EXTRA_OUTBOUND_GROUP).orEmpty()
        val outboundTag = intent.getStringExtra(VpnIntents.EXTRA_OUTBOUND_TAG).orEmpty()
        if (groupTag.isBlank() || outboundTag.isBlank()) {
            Timber.w("Outbound switch ignored: empty group=%s outbound=%s", groupTag, outboundTag)
            return
        }
        serviceScope?.launch {
            reconnectMutex.withLock {
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
        failoverJob?.cancel()
        unregisterNetworkCallback()
        // bridge.stop() ultimately calls into libbox which can hang on REALITY shutdown when the
        // server socket has not been gracefully torn down (this is a known sing-box pre-1.7
        // behaviour). Without a bound, runBlocking here will keep the main thread frozen long
        // enough that the OS force-stops the service and we never get to release the TUN fd or
        // unregister state. Bound the wait to BRIDGE_STOP_TIMEOUT_MS so that we still degrade
        // gracefully — the OS will reap the leftover goroutines when the process dies.
        val bridgeRef = bridge
        runCatching {
            runBlocking {
                withTimeoutOrNull(BRIDGE_STOP_TIMEOUT_MS) {
                    bridgeRef?.stop()
                } ?: Timber.w("bridge.stop() exceeded %d ms in onDestroy, giving up", BRIDGE_STOP_TIMEOUT_MS)
            }
        }.onFailure { Timber.w(it, "bridge.stop() threw in onDestroy") }
        bridge = null
        startJob = null
        failoverJob = null
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
        failoverJob?.cancelAndJoin()
        failoverJob = null
        reconnectWakeJob?.cancelAndJoin()
        reconnectWakeJob = null
        bridge?.stop()
        bridge = null
        activeServer = null
        connectedAt = null
        reconnectAttempt = 0
        sleepingForNetwork = false
        manualValidationWarning = null
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
        // Only attach the warning when the state is Connected — Reconnecting/Connecting/Error
        // already convey their own status and the warning is only meaningful for the steady
        // "tunnel up" notification.
        val warning = manualValidationWarning?.takeIf { state is VpnState.Connected }
        nm.notify(
            VpnNotifier.NOTIFICATION_ID,
            notifier.build(
                state = state,
                openAppPendingIntent = openAppPendingIntent(),
                manualWarning = warning,
            ),
        )
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
        const val AUTO_SHORTLIST_LIMIT = 12
        // We test up to 6 servers (was 4) so a single bad first pick can't dominate the result on
        // mobile / whitelist networks where the first reachable candidate often shows artificially
        // low speed because of DPI throttling on the speed-test endpoint.
        const val AUTO_TUNNEL_VALIDATION_LIMIT = 6
        // Require at least 5 candidates tested before short-circuiting (was 3). Combined with the
        // higher good-enough threshold this guarantees we sample enough of the shortlist to find
        // a really fast server rather than stopping at the first "barely OK" one.
        const val AUTO_MIN_TESTED_AFTER_SUCCESS = 5
        // Only short-circuit on >= 8 Mbps (was 1.5 Mbps). A 1.5 Mbps server is "alive" but it is
        // not "fast" — that was the previous bug that made AUTO settle for the first usable server
        // instead of the actually fastest one.
        const val AUTO_GOOD_ENOUGH_SPEED_KBPS = 8_000L
        const val MANUAL_VALIDATION_WARMUP_MS = 1_200L
        const val FIRST_SELECTOR_SWITCH_WARMUP_MS = 1_800L
        const val SELECTOR_SWITCH_WARMUP_MS = 1_000L
        // Mirrors com.lisvpn.android.vpn.config.SingBoxConfigBuilder.AUTO_TAG. Kept private over
        // there to keep the builder's surface tight; here it's just the wire string we hand back
        // to libbox via selectOutbound().
        const val AUTO_SELECTOR_TAG = "auto"
        // Hard ceiling for how long we let the libbox runtime tear itself down on the main
        // thread inside onDestroy. Past this, the Android service supervisor will start
        // ANR'ing us anyway, so giving up cleanly and letting the process exit is strictly
        // better than freezing the UI thread.
        const val BRIDGE_STOP_TIMEOUT_MS = 2_000L
    }
}
