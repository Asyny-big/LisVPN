package com.lisvpn.android.vpn.libbox

import android.net.VpnService
import com.lisvpn.android.core.domain.model.AppRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libbox.BoxService
import libbox.Libbox
import timber.log.Timber
import java.io.File

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

            val newPlatform = LisPlatformInterface(service = service, appRules = appRules)
            platform = newPlatform
            val newBox = Libbox.newService(configJson, newPlatform)
            box = newBox
            newBox.start()
            Timber.i("libbox started, version=%s", Libbox.version())
        }.onFailure {
            runCatching { box?.close() }
            platform?.closeTun()
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

    /** Stops and tears down. Idempotent. */
    suspend fun stop(): Result<Unit> = mutex.withLock {
        runCatching {
            Timber.i("libbox stop requested")
            try {
                box?.close()
            } finally {
                box = null
                platform?.closeTun()
                platform = null
            }
            Timber.i("libbox stopped")
        }.onFailure { Timber.e(it, "libbox stop failed") }
    }

    fun isRunning(): Boolean = box != null

    private fun ensureInitialized() {
        Companion.ensureInitialized(service)
    }

    private companion object {
        private const val FIX_ANDROID_STACK = true

        @Volatile private var initialized = false

        fun ensureInitialized(service: VpnService) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return

                val appContext = service.applicationContext
                val baseDir = appContext.filesDir.apply { mkdirs() }
                val workingDir = (appContext.getExternalFilesDir(null) ?: baseDir).apply { mkdirs() }
                val tempDir = appContext.cacheDir.apply { mkdirs() }
                val logDir = File(workingDir, "libbox-logs").apply { mkdirs() }
                val stderrFile = File(logDir, "libbox-stderr.log")

                Libbox.setup(
                    baseDir.absolutePath,
                    workingDir.absolutePath,
                    tempDir.absolutePath,
                    FIX_ANDROID_STACK,
                )
                runCatching { Libbox.redirectStderr(stderrFile.absolutePath) }
                    .onFailure { Timber.w(it, "libbox stderr redirect failed") }

                initialized = true
                Timber.i(
                    "libbox initialized: version=%s base=%s working=%s temp=%s stderr=%s",
                    runCatching { Libbox.version() }.getOrDefault("unknown"),
                    baseDir.absolutePath,
                    workingDir.absolutePath,
                    tempDir.absolutePath,
                    stderrFile.absolutePath,
                )
            }
        }
    }
}
