package com.lisvpn.android.vpn.libbox

import android.content.Context
import libbox.Libbox
import timber.log.Timber
import java.io.File

object LibboxEnvironment {
    private const val FIX_ANDROID_STACK = true

    @Volatile private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val appContext = context.applicationContext
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
