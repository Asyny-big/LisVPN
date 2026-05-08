package com.lisvpn.android.vpn.libbox

import android.net.VpnService
import com.lisvpn.android.core.domain.model.AppRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libbox.BoxService
import libbox.CommandClientHandler
import libbox.CommandClientOptions
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.Libbox
import libbox.OutboundGroupIterator
import libbox.StatusMessage
import libbox.StringIterator
import libbox.SystemProxyStatus
import timber.log.Timber

/**
 * Process-wide bridge to the sing-box runtime (libbox.aar). Exactly one [BoxService] may exist
 * at any time per OS process — this class enforces that invariant.
 *
 * Threading: all libbox calls are blocking JNI calls. The bridge is deliberately not coroutine-
 * aware internally — call from [com.lisvpn.android.vpn.core.LisVpnService] on a dedicated
 * dispatcher (`Dispatchers.IO` is fine; sing-box uses its own goroutines).
 */
class LibboxBridge(
    private val service: VpnService,
    private val configJson: String,
    private val appRules: AppRules,
) {

    private val mutex = Mutex()
    @Volatile private var box: BoxService? = null
    @Volatile private var platform: LisPlatformInterface? = null
    @Volatile private var commandServer: CommandServer? = null

    /**
     * Validates the supplied [configJson] without actually starting the tunnel.
     * Useful in `feature/profiles` to surface parse errors at import time.
     */
    suspend fun validate(): Result<Unit> = mutex.withLock {
        runCatching {
            ensureInitialized()
            Libbox.checkConfig(configJson)
        }
            .onFailure { Timber.w(it, "libbox.checkConfig failed") }
            .map { }
    }

    /**
     * Starts sing-box. Must be called from an active [VpnService] (because openTun() needs
     * the [VpnService.Builder]). Returns once `BoxService.start()` returns; the tunnel may
     * still be coming up — observe state from the calling controller.
     */
    suspend fun start(): Result<Unit> = mutex.withLock {
        runCatching {
            check(box == null) { "BoxService already running" }
            ensureInitialized()
            Libbox.setMemoryLimit(true)

            // Log libbox version up front so that bug reports always identify which sing-box
            // build the user was on. Specific protocols (notably VLESS+REALITY+Vision) have
            // had compatibility fixes across releases, so the version is critical context.
            Timber.tag("libbox-runtime").i("libbox version=%s configBytes=%d", Libbox.version(), configJson.length)

            val newPlatform = LisPlatformInterface(service = service, appRules = appRules)
            platform = newPlatform
            val newBox = Libbox.newService(configJson, newPlatform)
            box = newBox
            newBox.start()
            startCommandServer(newBox)
            Timber.i("libbox started, version=%s", Libbox.version())
        }.onFailure {
            runCatching { commandServer?.close() }
            runCatching { box?.close() }
            platform?.closeTun()
            commandServer = null
            box = null
            platform = null
            Timber.e(it, "libbox start failed")
        }
    }

    /** Soft pause: TUN stays open, network goroutines suspend. Use during transient connectivity loss. */
    suspend fun sleep(): Result<Unit> = mutex.withLock {
        runCatching { box?.sleep() ?: Unit }
    }

    /** Wake from [sleep]. */
    suspend fun wake(): Result<Unit> = mutex.withLock {
        runCatching { box?.wake() ?: Unit }
    }

    /** Switches a running selector group without creating another BoxService. */
    suspend fun selectOutbound(groupTag: String, outboundTag: String): Result<Unit> = mutex.withLock {
        runCatching {
            check(box != null) { "BoxService is not running" }
            runCommandClient(Libbox.CommandSelectOutbound) { client ->
                client.selectOutbound(groupTag, outboundTag)
            }
        }.onFailure { Timber.e(it, "libbox select outbound failed") }
    }

    /** Stops and tears down. Idempotent. */
    suspend fun stop(): Result<Unit> = mutex.withLock {
        runCatching {
            Timber.i("libbox stop requested")
            try {
                commandServer?.close()
                box?.close()
            } finally {
                commandServer = null
                box = null
                platform?.closeTun()
                platform = null
            }
            Timber.i("libbox stopped")
        }.onFailure { Timber.e(it, "libbox stop failed") }
    }

    fun isRunning(): Boolean = box != null

    private fun ensureInitialized() {
        LibboxEnvironment.ensureInitialized(service)
    }

    private fun startCommandServer(runningBox: BoxService) {
        runCatching {
            val server = Libbox.newCommandServer(NoopCommandServerHandler, COMMAND_LOG_LINES)
            server.setService(runningBox)
            server.start()
            commandServer = server
            Timber.i("libbox command server started")
        }.onFailure { Timber.w(it, "libbox command server unavailable; selector switching disabled") }
    }

    private fun runCommandClient(command: Int, block: (libbox.CommandClient) -> Unit) {
        runCatching {
            val client = Libbox.newStandaloneCommandClient()
            block(client)
        }.recoverCatching {
            val options = CommandClientOptions().apply {
                setCommand(command)
                setStatusInterval(0L)
            }
            val client = Libbox.newCommandClient(NoopCommandClientHandler, options)
            client.connect()
            try {
                block(client)
            } finally {
                runCatching { client.disconnect() }
            }
        }.getOrThrow()
    }

    private object NoopCommandServerHandler : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply {
                setAvailable(false)
                setEnabled(false)
            }

        override fun serviceReload() = Unit
        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    }

    private object NoopCommandClientHandler : CommandClientHandler {
        override fun clearLog() = Unit
        override fun connected() = Unit
        override fun disconnected(message: String?) {
            if (!message.isNullOrBlank()) Timber.d("libbox command client disconnected: %s", message)
        }
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeGroups(groups: OutboundGroupIterator?) = Unit
        override fun writeLog(message: String?) {
            if (!message.isNullOrBlank()) Timber.tag("libbox-command").d(message)
        }
        override fun writeStatus(message: StatusMessage?) = Unit
    }

    private companion object {
        const val COMMAND_LOG_LINES = 256
    }
}
