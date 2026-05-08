package com.lisvpn.android.vpn.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ThroughputWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun measure(): ThroughputResult = withContext(ioDispatcher) {
        val network = findVpnNetwork() ?: return@withContext ThroughputResult(
            success = false,
            bytesRead = 0L,
            elapsedMs = null,
            firstByteMs = null,
            mbps = null,
            error = "vpn network not visible",
        )
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            measureOn(network)
        } ?: ThroughputResult(
            success = false,
            bytesRead = 0L,
            elapsedMs = null,
            firstByteMs = null,
            mbps = null,
            error = "timeout",
        )
    }

    private fun measureOn(network: Network): ThroughputResult {
        val startedAt = SystemClock.elapsedRealtime()
        var bytes = 0L
        var firstByteMs: Int? = null
        return runCatching {
            val connection = (network.openConnection(URL(SPEED_URL)) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    return@runCatching ThroughputResult(
                        success = false,
                        bytesRead = 0L,
                        elapsedMs = elapsedSince(startedAt),
                        firstByteMs = null,
                        mbps = null,
                        error = "http $code",
                    )
                }
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (bytes < MAX_DOWNLOAD_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, (MAX_DOWNLOAD_BYTES - bytes).toInt()))
                        if (read <= 0) break
                        if (firstByteMs == null) firstByteMs = elapsedSince(startedAt)
                        bytes += read
                    }
                }
                val elapsedMs = elapsedSince(startedAt).coerceAtLeast(1)
                val mbps = (bytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1_000.0
                ThroughputResult(
                    success = bytes > 0,
                    bytesRead = bytes,
                    elapsedMs = elapsedMs,
                    firstByteMs = firstByteMs,
                    mbps = mbps.takeIf { bytes > 0 },
                    error = null,
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            ThroughputResult(
                success = false,
                bytesRead = bytes,
                elapsedMs = elapsedSince(startedAt),
                firstByteMs = firstByteMs,
                mbps = null,
                error = "${err::class.java.simpleName}: ${err.message.orEmpty()}".take(96),
            )
        }
    }

    private fun findVpnNetwork(): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private companion object {
        const val USER_AGENT = "LisVPN/MiniSpeed"
        const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=200000"
        const val MAX_DOWNLOAD_BYTES = 200_000L
        const val BUFFER_SIZE = 16 * 1024
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
        const val TOTAL_TIMEOUT_MS = 7_000L
    }
}
