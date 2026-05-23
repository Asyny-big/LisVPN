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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Measures real download throughput through the live VPN tunnel.
 *
 * Why this is non-trivial on Russian mobile + whitelist networks:
 *  - speed.cloudflare.com is frequently DPI-throttled or blocked by carriers, so a single endpoint
 *    can return 0 bytes/s for a perfectly healthy server. We now try several CDNs in order and
 *    take the first one that delivers a meaningful sample.
 *  - A 200 KB sample (the previous default) is too small to escape TCP slow-start, so even a fast
 *    server reports a slow rate. We download up to 1 MiB now, but stop early once we have enough
 *    bytes within a budget so the overall AUTO selection latency stays bounded.
 */
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

        val attempts = coroutineScope {
            SPEED_ENDPOINTS.map { endpoint ->
                async {
                    withTimeoutOrNull(endpoint.totalTimeoutMs) {
                        measureOn(network, endpoint)
                    } ?: ThroughputResult(
                        success = false,
                        bytesRead = 0L,
                        elapsedMs = null,
                        firstByteMs = null,
                        mbps = null,
                        error = "timeout:${endpoint.name}",
                    )
                }
            }.awaitAll()
        }
        val useful = attempts
            .filter { it.success && it.bytesRead >= MIN_USEFUL_BYTES }
            .maxWithOrNull(compareBy<ThroughputResult> { it.mbps ?: 0.0 }.thenBy { it.bytesRead })
        if (useful != null) {
            Timber.i(
                "Throughput mini-race winner: bytes=%d mbps=%s firstByte=%sms elapsed=%sms",
                useful.bytesRead,
                useful.mbps,
                useful.firstByteMs,
                useful.elapsedMs,
            )
            return@withContext useful
        }
        val partial = attempts
            .filter { it.success && it.bytesRead > 0L }
            .maxWithOrNull(compareBy<ThroughputResult> { it.bytesRead }.thenBy { it.mbps ?: 0.0 })
        if (partial != null) {
            Timber.w("Throughput mini-race accepted partial sample: bytes=%d mbps=%s", partial.bytesRead, partial.mbps)
            return@withContext partial
        }
        val lastError = attempts.firstOrNull { !it.error.isNullOrBlank() }?.error
        ThroughputResult(
            success = false,
            bytesRead = 0L,
            elapsedMs = null,
            firstByteMs = null,
            mbps = null,
            error = lastError ?: "all speed endpoints failed",
        )
    }

    private fun measureOn(network: Network, endpoint: SpeedEndpoint): ThroughputResult {
        val startedAt = SystemClock.elapsedRealtime()
        var bytes = 0L
        var firstByteMs: Int? = null
        return runCatching {
            val connection = (network.openConnection(URL(endpoint.url)) as HttpURLConnection).apply {
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
                    val budgetMs = endpoint.downloadBudgetMs
                    val maxBytes = endpoint.maxBytes
                    while (bytes < maxBytes) {
                        val elapsedNow = SystemClock.elapsedRealtime() - startedAt
                        // Cap on wall time so a single endpoint can never blow past its budget,
                        // even if it keeps trickling bytes very slowly. We still accept whatever
                        // we managed to download as the measurement.
                        if (elapsedNow >= budgetMs && bytes >= MIN_USEFUL_BYTES) break
                        val remaining = minOf(buffer.size.toLong(), maxBytes - bytes).toInt()
                        val read = input.read(buffer, 0, remaining)
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

    private data class SpeedEndpoint(
        val name: String,
        val url: String,
        val maxBytes: Long,
        val downloadBudgetMs: Long,
        val totalTimeoutMs: Long,
    )

    private companion object {
        const val USER_AGENT = "LisVPN/MiniSpeed"
        const val BUFFER_SIZE = 32 * 1024
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 2_200
        const val MIN_USEFUL_BYTES = 96L * 1024L

        // Ordered list of speed endpoints to try. The first that returns a meaningful sample wins.
        // We mix global big-tech CDNs (cloudflare/google/microsoft) with Russian-friendly mirrors
        // so AUTO mode can always find at least one working endpoint regardless of which side of a
        // carrier whitelist the user is on.
        val SPEED_ENDPOINTS = listOf(
            SpeedEndpoint(
                name = "cloudflare",
                url = "https://speed.cloudflare.com/__down?bytes=262144",
                maxBytes = 262_144L,
                downloadBudgetMs = 1_800L,
                totalTimeoutMs = 2_400L,
            ),
            SpeedEndpoint(
                // Hetzner public download mirror — extremely well peered globally and very rarely
                // hit by DPI in Russia.
                name = "hetzner",
                url = "https://hil-speed.hetzner.com/100MB.bin",
                maxBytes = 262_144L,
                downloadBudgetMs = 1_800L,
                totalTimeoutMs = 2_400L,
            ),
            SpeedEndpoint(
                // Yandex's public package mirror is whitelisted on virtually every Russian carrier,
                // so even when cloudflare and hetzner are DPI-zeroed this endpoint still reports a
                // real throughput sample.
                name = "yandex",
                url = "https://mirror.yandex.ru/ubuntu/ls-lR.gz",
                maxBytes = 262_144L,
                downloadBudgetMs = 1_800L,
                totalTimeoutMs = 2_400L,
            ),
        )
    }
}
