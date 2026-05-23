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
import android.os.SystemClock
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.ConnectedServer
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.model.VpnState
import com.lisvpn.android.core.domain.repository.AppRulesRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerRepository
import com.lisvpn.android.core.domain.repository.AutoOptimizerStage
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
import com.lisvpn.android.vpn.health.SmartServerHistory
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
    private var networkRefreshJob: Job? = null
    private var failoverJob: Job? = null
    private var sleepingForNetwork: Boolean = false
    private var activeUnderlyingNetworkKey: String? = null
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
            // Register the network callback BEFORE any manual/AUTO tunnel validation. Validation
            // is exactly the period where mobile networks flip, captive portals wake up, or radio
            // reconnects; missing onLost/onAvailable here made AUTO appear connected to a dead
            // route. registerNetworkCallback() is idempotent and cheap.
            registerNetworkCallback()
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
            activeUnderlyingNetworkKey = currentUnderlyingNetworkKey()
            registerNetworkCallback()
            controller.publishConnected(cs, now)
            updateNotification(VpnState.Connected(cs, now))
            autoResult?.let { startAutoFailover(it) }
        }
    }

    private suspend fun buildAutoSelectionPlan(candidates: List<Server>): AutoSelectionPlan? {
        val selectionStartedAt = SystemClock.elapsedRealtime()
        val profile = smartServerCache.currentProfile()
        val tagged = candidates.mapIndexed { index, server -> TaggedServer(server, "srv-$index") }
        val histories = smartServerCache.histories(profile, tagged.map { it.server.id })
        val stickyServerIds = smartServerCache.bestServerIds(profile, AUTO_STICKY_SERVER_COUNT).toSet()
        val fastTargets = buildFastProbeTargets(
            tagged = tagged,
            histories = histories,
            stickyServerIds = stickyServerIds,
            profile = profile,
        )
        autoOptimizerRepository.report(
            AutoOptimizerStatus.Probing(
                current = 0,
                total = fastTargets.size,
                serverDisplayName = "",
                stage = AutoOptimizerStage.FastFilter,
                stageMessage = "Поиск лучшего маршрута… Проверяем доступность",
                progressPercent = 0,
                checked = 0,
                reachable = 0,
                debugSummary = "network=${profile.networkClass} candidates=${tagged.size} fast=${fastTargets.size} sticky=${stickyServerIds.size}",
            ),
        )
        val fastStageStartedAt = SystemClock.elapsedRealtime()
        val fastResults = fastProbeWorker.probeAll(fastTargets) { completed, total, reachable, result ->
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Probing(
                    current = completed,
                    total = total,
                    serverDisplayName = result.taggedServer.server.displayName,
                    stage = AutoOptimizerStage.FastFilter,
                    stageMessage = "Проверяем TCP/DNS/минимальный handshake",
                    progressPercent = progressPercent(completed, total),
                    estimatedRemainingMs = estimateRemainingMs(fastStageStartedAt, completed, total),
                    checked = completed,
                    reachable = reachable,
                    debugSummary = "last=${if (result.success) "ok/${result.latencyMs}ms" else result.failureReason} pool=${fastTargets.size}/${tagged.size}",
                ),
            )
        }
        val shortlist = scoreCalculator.shortlist(
            fastResults = fastResults,
            histories = histories,
            profile = profile,
            limit = AUTO_SHORTLIST_LIMIT,
        )
        Timber.i(
            "AUTO telemetry stage=fast_filter network=%s fingerprint=%s total=%d probed=%d reachable=%d sticky=%s shortlist=%s failures=%s elapsedMs=%d",
            profile.networkClass,
            profile.fingerprint,
            tagged.size,
            fastTargets.size,
            fastResults.count { it.success },
            stickyServerIds.joinToString(),
            shortlist.joinToString { "${it.server.displayName}/${it.fastProbe.latencyMs}ms" },
            fastResults.filterNot { it.success }.take(6).joinToString { "${it.taggedServer.server.displayName}:${it.failureReason}" },
            elapsedSince(fastStageStartedAt),
        )
        if (shortlist.isEmpty()) {
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Failed(
                    reason = "Нет доступных серверов после быстрой проверки",
                    stage = AutoOptimizerStage.FastFilter,
                    tested = fastResults.size,
                    total = fastTargets.size,
                    debugSummary = fastResults.take(8).joinToString { "${it.taggedServer.server.displayName}:${it.failureReason ?: it.latencyMs}" },
                ),
            )
            return null
        }
        return AutoSelectionPlan(
            profile = profile,
            shortlist = shortlist,
            fastResults = fastResults,
            selectionStartedAtMs = selectionStartedAt,
        )
    }

    private suspend fun runAutoTunnelValidation(
        runningBridge: LibboxBridge,
        plan: AutoSelectionPlan,
    ): AutoSelectionResult? {
        val validationLimit = validationLimit(plan.profile)
        val validationCandidates = plan.shortlist.take(validationLimit)
        val scored = mutableListOf<ScoredAutoServer>()
        var bestSoFar: ScoredAutoServer? = null
        val validationStartedAt = SystemClock.elapsedRealtime()
        for ((index, candidate) in validationCandidates.withIndex()) {
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Probing(
                    current = index + 1,
                    total = validationCandidates.size,
                    serverDisplayName = candidate.server.displayName,
                    lastSpeedKbps = bestSoFar?.throughput?.kbps,
                    lastServerDisplayName = bestSoFar?.server?.displayName,
                    stage = AutoOptimizerStage.TunnelValidation,
                    stageMessage = "Тестируем реальный интернет через VPN",
                    progressPercent = progressPercent(index, validationCandidates.size),
                    estimatedRemainingMs = estimateRemainingMs(validationStartedAt, index, validationCandidates.size),
                    checked = scored.size,
                    reachable = scored.count { it.eligible },
                    debugSummary = "fast=${candidate.fastProbe.latencyMs}ms hist=${candidate.history?.successRate}",
                ),
            )
            val switched = reconnectMutex.withLock {
                runningBridge.selectOutbound(AUTO_SELECTOR_TAG, candidate.outboundTag)
            }
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
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Probing(
                    current = index + 1,
                    total = validationCandidates.size,
                    serverDisplayName = candidate.server.displayName,
                    lastSpeedKbps = bestSoFar?.throughput?.kbps,
                    lastServerDisplayName = bestSoFar?.server?.displayName,
                    stage = if (validation.eligible) AutoOptimizerStage.SpeedTest else AutoOptimizerStage.TunnelValidation,
                    stageMessage = if (validation.eligible) "Найден рабочий туннель, измеряем скорость" else "Маршрут не прошёл HTTP/DNS проверку",
                    progressPercent = progressPercent(index + 1, validationCandidates.size),
                    estimatedRemainingMs = estimateRemainingMs(validationStartedAt, index + 1, validationCandidates.size),
                    checked = scored.size + 1,
                    reachable = scored.count { it.eligible } + if (validation.eligible) 1 else 0,
                    debugSummary = "http=${validation.successCount}/${validation.checkCount} rtt=${validation.averageRttMs}ms reason=${validation.failureReason}",
                ),
            )
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
            if (current.eligible && (bestSoFar == null || current.score > bestSoFar!!.score)) {
                bestSoFar = current
            }
            Timber.i(
                "AUTO telemetry stage=tunnel_candidate server=%s tag=%s eligible=%s score=%d speedKbps=%s http=%d/%d rtt=%sms jitter=%sms loss=%.2f reason=%s tested=%d/%d",
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
                scored.size,
                validationCandidates.size,
            )
            val best = bestSoFar
            if (best != null && shouldStopAutoValidation(scored.size, best, plan.profile)) {
                Timber.i(
                    "AUTO telemetry stage=early_winner best=%s tested=%d speedKbps=%s score=%d",
                    best.server.displayName,
                    scored.size,
                    best.throughput?.kbps,
                    scoreCalculator.diagnosticScore(best.score),
                )
                break
            }
        }

        autoOptimizerRepository.report(
            AutoOptimizerStatus.Probing(
                current = scored.size,
                total = validationCandidates.size,
                serverDisplayName = bestSoFar?.server?.displayName.orEmpty(),
                lastSpeedKbps = bestSoFar?.throughput?.kbps,
                lastServerDisplayName = bestSoFar?.server?.displayName,
                stage = AutoOptimizerStage.SelectingWinner,
                stageMessage = "Сравниваем latency, packet loss, историю и скорость",
                progressPercent = 100,
                checked = scored.size,
                reachable = scored.count { it.eligible },
            ),
        )
        val validated = scored
            .filter { it.eligible }
            .sortedWith(
                compareByDescending<ScoredAutoServer> { it.score }
                    .thenByDescending { it.throughput?.mbps ?: 0.0 }
                    .thenBy { it.validation.averageRttMs ?: Int.MAX_VALUE },
            )
        val best = validated.firstOrNull()
        if (best == null) {
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Failed(
                    reason = "Ни один сервер не подтвердил доступ в интернет через VPN",
                    stage = AutoOptimizerStage.TunnelValidation,
                    tested = scored.size,
                    total = validationCandidates.size,
                    debugSummary = scored.take(6).joinToString { "${it.server.displayName}:${it.validation.failureReason}" },
                ),
            )
            Timber.w(
                "AUTO selection failed: no validated server tested=%d candidates=%d failures=%s",
                scored.size,
                validationCandidates.size,
                scored.take(6).joinToString { "${it.server.displayName}:${it.validation.failureReason}" },
            )
            return null
        }
        val finalSwitch = reconnectMutex.withLock {
            runningBridge.selectOutbound(AUTO_SELECTOR_TAG, best.outboundTag)
        }
        if (finalSwitch.isFailure) {
            autoOptimizerRepository.report(
                AutoOptimizerStatus.Failed(
                    reason = "Не удалось переключиться на выбранный сервер",
                    stage = AutoOptimizerStage.SelectingWinner,
                    tested = scored.size,
                    total = validationCandidates.size,
                    debugSummary = finalSwitch.exceptionOrNull()?.message,
                ),
            )
            Timber.w(finalSwitch.exceptionOrNull(), "Failed to switch AUTO selector to validated winner")
            return null
        }
        autoOptimizerRepository.report(
            AutoOptimizerStatus.Done(
                bestServerDisplayName = best.server.displayName,
                bestSpeedKbps = best.throughput?.kbps,
                tested = scored.size,
                total = validationCandidates.size,
                elapsedMs = elapsedSince(plan.selectionStartedAtMs).toLong(),
                selectionReason = selectionReason(best, scored.size, plan.profile),
                debugSummary = "score=${scoreCalculator.diagnosticScore(best.score)} rtt=${best.validation.averageRttMs}ms loss=${best.validation.packetLossApprox}",
            ),
        )
        Timber.i(
            "AUTO telemetry stage=finished best=%s tag=%s score=%d validated=%d tested=%d speedKbps=%s elapsedMs=%d",
            best.server.displayName,
            best.outboundTag,
            scoreCalculator.diagnosticScore(best.score),
            validated.size,
            scored.size,
            best.throughput?.kbps,
            elapsedSince(plan.selectionStartedAtMs),
        )
        return AutoSelectionResult(profile = plan.profile, best = best, validated = validated)
    }

    private fun shouldStopAutoValidation(
        tested: Int,
        currentBest: ScoredAutoServer,
        profile: SmartNetworkProfile,
    ): Boolean {
        val speedKbps = currentBest.throughput?.kbps ?: 0L
        val rtt = currentBest.validation.averageRttMs ?: Int.MAX_VALUE
        val goodEnoughSpeed = if (profile.isMobileLike) AUTO_GOOD_ENOUGH_SPEED_KBPS_MOBILE else AUTO_GOOD_ENOUGH_SPEED_KBPS_FIXED
        val minTested = if (profile.isMobileLike) AUTO_MIN_TESTED_AFTER_SUCCESS_MOBILE else AUTO_MIN_TESTED_AFTER_SUCCESS_FIXED
        if (speedKbps >= goodEnoughSpeed && rtt <= AUTO_GOOD_ENOUGH_RTT_MS) return true
        if (tested >= minTested && currentBest.score > AUTO_GOOD_ENOUGH_SCORE) return true
        val stickyHealthy = currentBest.candidate.history?.lastSuccessAtMs != null && rtt <= AUTO_STICKY_ACCEPT_RTT_MS
        return stickyHealthy && tested >= 1 && speedKbps >= AUTO_STICKY_ACCEPT_SPEED_KBPS
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

    private fun buildFastProbeTargets(
        tagged: List<TaggedServer>,
        histories: Map<String, SmartServerHistory>,
        stickyServerIds: Set<String>,
        profile: SmartNetworkProfile,
    ): List<TaggedServer> {
        if (tagged.isEmpty()) return emptyList()
        val limit = fastFilterLimit(profile).coerceAtMost(tagged.size)
        val originalIndex = tagged.withIndex().associate { it.value.server.id to it.index }
        val ordered = linkedMapOf<String, TaggedServer>()
        fun add(server: TaggedServer) {
            if (ordered.size < limit) ordered.putIfAbsent(server.server.id, server)
        }

        tagged
            .filter { it.server.id in stickyServerIds }
            .sortedBy { stickyServerIds.indexOfStable(it.server.id) }
            .forEach(::add)

        tagged
            .sortedWith(
                compareByDescending<TaggedServer> { candidate ->
                    fastProbePriority(candidate, histories[candidate.server.id], profile)
                }.thenBy { candidate -> originalIndex[candidate.server.id] ?: Int.MAX_VALUE },
            )
            .forEach(::add)

        // Add a small geographic spread so one stale cache bucket cannot hide a working nearby
        // fallback. This is still bounded by [limit], so large subscriptions do not slow startup.
        tagged
            .groupBy { it.server.countryCode.orEmpty().uppercase().ifBlank { it.server.displayName.take(4) } }
            .values
            .mapNotNull { group -> group.minByOrNull { originalIndex[it.server.id] ?: Int.MAX_VALUE } }
            .forEach(::add)

        return ordered.values.toList()
    }

    private fun fastProbePriority(
        candidate: TaggedServer,
        history: SmartServerHistory?,
        profile: SmartNetworkProfile,
    ): Double {
        var priority = 1_000.0
        if (history != null) {
            priority += history.successRate * if (profile.isMobileLike) 500.0 else 350.0
            priority += (history.lastThroughputKbps ?: 0L).coerceAtMost(50_000L).toDouble() / 120.0
            priority -= (history.failureCount.coerceAtMost(5) * 35.0)
            if (history.lastSuccessAtMs != null) priority += 220.0
        }
        if (profile.isMobileLike && Server.Tag.MobileBypass in candidate.server.tags) priority += 120.0
        if (!profile.isMobileLike && Server.Tag.FastEdge in candidate.server.tags) priority += 60.0
        if (Server.Tag.Primary in candidate.server.tags) priority += 50.0
        if (Server.Tag.Backup in candidate.server.tags) priority -= 30.0
        return priority
    }

    private fun Set<String>.indexOfStable(value: String): Int {
        var index = 0
        for (item in this) {
            if (item == value) return index
            index += 1
        }
        return Int.MAX_VALUE
    }

    private fun fastFilterLimit(profile: SmartNetworkProfile): Int =
        if (profile.isMobileLike) AUTO_FAST_FILTER_LIMIT_MOBILE else AUTO_FAST_FILTER_LIMIT_FIXED

    private fun validationLimit(profile: SmartNetworkProfile): Int =
        if (profile.isMobileLike) AUTO_TUNNEL_VALIDATION_LIMIT_MOBILE else AUTO_TUNNEL_VALIDATION_LIMIT_FIXED

    private fun progressPercent(done: Int, total: Int): Int? =
        total.takeIf { it > 0 }?.let { ((done.coerceIn(0, it).toDouble() / it.toDouble()) * 100.0).toInt().coerceIn(0, 100) }

    private fun estimateRemainingMs(startedAt: Long, done: Int, total: Int): Long? {
        if (done <= 0 || total <= 0 || done >= total) return null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val average = elapsed / done.coerceAtLeast(1)
        return average * (total - done)
    }

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private fun selectionReason(best: ScoredAutoServer, tested: Int, profile: SmartNetworkProfile): String {
        val speed = best.throughput?.kbps?.let { "${it / 1_000}.${(it % 1_000) / 100} Мбит/с" }
        val rtt = best.validation.averageRttMs?.let { "RTT ${it} мс" }
        val stability = if (best.candidate.history?.lastSuccessAtMs != null) "есть успешная история" else null
        return listOfNotNull(
            "score ${scoreCalculator.diagnosticScore(best.score)}",
            speed,
            rtt,
            stability,
            if (profile.isMobileLike) "мобильный профиль" else null,
            "проверено $tested",
        ).joinToString(" · ")
    }

    private data class AutoSelectionPlan(
        val profile: SmartNetworkProfile,
        val shortlist: List<AutoSelectionCandidate>,
        val fastResults: List<FastProbeResult>,
        val selectionStartedAtMs: Long,
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
        networkRefreshJob?.cancel()
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
                    } else {
                        scheduleUnderlyingNetworkRefresh(runningBridge, "Underlying network lost/replaced")
                    }
                }
            }

            override fun onAvailable(network: Network) {
                serviceScope?.launch {
                    if (isVpnNetwork(network)) return@launch
                    val runningBridge = bridge
                    if (runningBridge == null || !runningBridge.isRunning()) return@launch
                    if (sleepingForNetwork) scheduleNetworkWake(runningBridge)
                    else scheduleUnderlyingNetworkRefresh(runningBridge, "Underlying network available")
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                serviceScope?.launch {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@launch
                    val runningBridge = bridge ?: return@launch
                    if (!runningBridge.isRunning()) return@launch
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        if (sleepingForNetwork) scheduleNetworkWake(runningBridge)
                        else scheduleUnderlyingNetworkRefresh(runningBridge, "Underlying network capabilities changed")
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
        activeUnderlyingNetworkKey = currentUnderlyingNetworkKey()
        controller.publishConnected(server, at)
        updateNotification(VpnState.Connected(server, at))
    }

    private suspend fun resetRuntime() {
        unregisterNetworkCallback()
        failoverJob?.cancelAndJoin()
        failoverJob = null
        networkRefreshJob?.cancelAndJoin()
        networkRefreshJob = null
        reconnectWakeJob?.cancelAndJoin()
        reconnectWakeJob = null
        bridge?.stop()
        bridge = null
        activeServer = null
        connectedAt = null
        reconnectAttempt = 0
        sleepingForNetwork = false
        manualValidationWarning = null
        activeUnderlyingNetworkKey = null
    }

    private suspend fun suspendForNetwork(runningBridge: LibboxBridge, reason: String) {
        if (sleepingForNetwork) return
        reconnectWakeJob?.cancel()
        reconnectWakeJob = null
        sleepingForNetwork = true
        reconnectAttempt += 1
        Timber.w("%s: attempt=%d", reason, reconnectAttempt)
        reconnectMutex.withLock { runningBridge.sleep() }
        val state = VpnState.Reconnecting(reconnectAttempt, activeServer?.displayName)
        controller.publishReconnecting(reconnectAttempt)
        updateNotification(state)
    }

    private fun scheduleUnderlyingNetworkRefresh(runningBridge: LibboxBridge, reason: String) {
        if (activeServer == null) return
        val nextKey = currentUnderlyingNetworkKey() ?: return
        val previousKey = activeUnderlyingNetworkKey
        if (previousKey == null) {
            activeUnderlyingNetworkKey = nextKey
            return
        }
        if (previousKey == nextKey) return
        activeUnderlyingNetworkKey = nextKey
        networkRefreshJob?.cancel()
        networkRefreshJob = serviceScope?.launch {
            delay(NETWORK_SWITCH_DEBOUNCE_MS)
            if (!hasUsableNetwork()) return@launch
            if (bridge !== runningBridge || !runningBridge.isRunning()) return@launch
            reconnectAttempt += 1
            val state = VpnState.Reconnecting(reconnectAttempt, activeServer?.displayName)
            Timber.i(
                "Underlying network changed: reason=%s from=%s to=%s attempt=%d",
                reason,
                previousKey,
                nextKey,
                reconnectAttempt,
            )
            controller.publishReconnecting(reconnectAttempt)
            updateNotification(state)
            reconnectMutex.withLock {
                runningBridge.sleep()
                delay(NETWORK_SWITCH_WAKE_DELAY_MS)
                runningBridge.wake()
            }.onSuccess {
                publishConnectedAgain()
            }.onFailure { err ->
                Timber.e(err, "libbox network refresh failed after underlying network switch")
                sleepingForNetwork = true
                scheduleNetworkWake(runningBridge)
            }
        }
    }

    private fun scheduleNetworkWake(runningBridge: LibboxBridge) {
        if (!sleepingForNetwork) return
        reconnectWakeJob?.cancel()
        val delayMs = reconnectDelayMs(reconnectAttempt)
        reconnectWakeJob = serviceScope?.launch {
            Timber.i("Default network available, wake delayed by %d ms", delayMs)
            delay(delayMs)
            if (!hasUsableNetwork()) return@launch
            reconnectMutex.withLock { runningBridge.wake() }
                .onSuccess {
                    activeUnderlyingNetworkKey = currentUnderlyingNetworkKey()
                    publishConnectedAgain()
                }
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

    private fun currentUnderlyingNetworkKey(): String? {
        val best = connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                network to caps
            }
            .maxByOrNull { (_, caps) -> caps.underlyingPriority() }
            ?: return null
        val (network, caps) = best
        return buildString {
            append(network.toString())
            append(':')
            append(caps.transportLabel())
            append(':')
            append(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "unvalidated")
        }
    }

    private fun NetworkCapabilities.underlyingPriority(): Int {
        val validated = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
        val internet = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 50 else 0
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 40
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 35
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 30
            else -> 10
        }
        return validated + internet + transport
    }

    private fun NetworkCapabilities.transportLabel(): String = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
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
        const val NETWORK_SWITCH_DEBOUNCE_MS = 900L
        const val NETWORK_SWITCH_WAKE_DELAY_MS = 350L
        const val RECONNECT_BASE_DELAY_MS = 1_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val RECONNECT_JITTER_MS = 1_500L
        const val RECONNECT_MAX_EXPONENT = 5
        const val AUTO_SHORTLIST_LIMIT = 10
        const val AUTO_STICKY_SERVER_COUNT = 4
        const val AUTO_FAST_FILTER_LIMIT_FIXED = 28
        const val AUTO_FAST_FILTER_LIMIT_MOBILE = 18
        const val AUTO_TUNNEL_VALIDATION_LIMIT_FIXED = 5
        const val AUTO_TUNNEL_VALIDATION_LIMIT_MOBILE = 4
        const val AUTO_MIN_TESTED_AFTER_SUCCESS_FIXED = 3
        const val AUTO_MIN_TESTED_AFTER_SUCCESS_MOBILE = 2
        const val AUTO_GOOD_ENOUGH_SPEED_KBPS_FIXED = 5_000L
        const val AUTO_GOOD_ENOUGH_SPEED_KBPS_MOBILE = 2_500L
        const val AUTO_GOOD_ENOUGH_RTT_MS = 1_200
        const val AUTO_GOOD_ENOUGH_SCORE = 45.0
        const val AUTO_STICKY_ACCEPT_RTT_MS = 900
        const val AUTO_STICKY_ACCEPT_SPEED_KBPS = 1_200L
        const val MANUAL_VALIDATION_WARMUP_MS = 900L
        const val FIRST_SELECTOR_SWITCH_WARMUP_MS = 700L
        const val SELECTOR_SWITCH_WARMUP_MS = 350L
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
