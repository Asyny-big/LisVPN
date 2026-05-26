package com.lisvpn.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lisvpn.android.core.common.logging.LisFileLogTree
import com.lisvpn.android.core.common.logging.LisLogExporter
import com.lisvpn.android.core.common.logging.LisLogTree
import com.lisvpn.android.subscription.SubscriptionRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timber.log.Timber

/**
 * Application entry point.
 *
 * Responsibilities:
 *  - Hilt DI graph bootstrapping ([HiltAndroidApp]).
 *  - WorkManager configuration with Hilt-aware factory ([Configuration.Provider]).
 *  - Logging tree installation (Timber → Logcat in debug, structured production tree elsewhere).
 *
 * Heavy initialisation (libbox runtime, health probe scheduling, etc.) is intentionally NOT
 * performed here — it is deferred to first VPN connection or a dedicated initialiser. Cold start
 * has to remain fast, especially on low-end Android 8 devices.
 */
@HiltAndroidApp
class LisVpnApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logExporter: LisLogExporter

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(LisLogTree(isDebug = BuildConfig.DEBUG))
        Timber.plant(LisFileLogTree(logExporter, isDebug = BuildConfig.DEBUG))
        val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Timber.e(throwable, "Uncaught exception: thread=%s", thread.name)
            }
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
        SubscriptionRefreshWorker.schedule(this)
        Timber.i("LisVPN application created (versionName=%s, applicationId=%s)", BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID)
    }
}
