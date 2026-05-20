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

        val measurementStartedAt = SystemClock.elapsedRealtime()
        var lastError: String? = null
        for (endpoint in SPEED_ENDPOINTS) {
            val elapsedSoFar = SystemClock.elapsedRealtime() - measurementStartedAt
            val remainingBudget = TOTAL_MEASUREMENT_BUDGET_MS - elapsedSoFar
            if (remainingBudget <= MIN_REMAINING_BUDGET_MS) break
            val perEndpointTimeout = remainingBudget.coerceAtMost(endpoint.totalTimeoutMs)
            val attempt = withTimeoutOrNull(perEndpointTimeout) {
                measureOn(network, endpoint)
            } ?: ThroughputResult(
                success = false,
                bytesRead = 0L,
                elapsedMs = null,
                firstByteMs = null,
                mbps = null,
                error = "timeout",
            )
            // We only accept a measurement that downloaded enough bytes to escape TCP slow-start.
            // Anything below MIN_USEFUL_BYTES (256 KB) is treated as a probe failure so we move on
            // to the next CDN instead of recording a misleadingly tiny "speed".
            if (attempt.success && attempt.bytesRead >= MIN_USEFUL_BYTES) {
                if (endpoint != SPEED_ENDPOINTS.first()) {
                    Timber.i(
                        "Throughput fallback used: endpoint=%s bytes=%d mbps=%s",
                        endpoint.url,
                        attempt.bytesRead,
                        attempt.mbps,
                    )
                }
                return@withContext attempt
            }
            lastError = attempt.error
                ?: if (attempt.success) "sample too small (${attempt.bytesRead} bytes)" else "unknown"
            Timber.w(
                "Throughput endpoint failed: endpoint=%s bytes=%d error=%s",
                endpoint.url,
                attempt.bytesRead,
                lastError,
            )
        }
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
        val url: String,
        val maxBytes: Long,
        val downloadBudgetMs: Long,
        val totalTimeoutMs: Long,
    )

    private companion object {
        const val USER_AGENT = "LisVPN/MiniSpeed"
        const val BUFFER_SIZE = 32 * 1024
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
        const val MIN_USEFUL_BYTES = 256L * 1024L
        // Total wall-clock cap for the entire measurement, including all fallbacks. Even if every
        // endpoint times out we will not block AUTO selection longer than this.
        const val TOTAL_MEASUREMENT_BUDGET_MS = 9_000L
        const val MIN_REMAINING_BUDGET_MS = 500L

        // Ordered list of speed endpoints to try. The first that returns a meaningful sample wins.
        // We mix global big-tech CDNs (cloudflare/google/microsoft) with Russian-friendly mirrors
        // so AUTO mode can always find at least one working endpoint regardless of which side of a
        // carrier whitelist the user is on.
        val SPEED_ENDPOINTS = listOf(
            SpeedEndpoint(
                url = "https://speed.cloudflare.com/__down?bytes=1048576",
                maxBytes = 1_048_576L,
                downloadBudgetMs = 3_500L,
                totalTimeoutMs = 5_000L,
            ),
            SpeedEndpoint(
                // Hetzner public download mirror — extremely well peered globally and very rarely
                // hit by DPI in Russia.
                url = "https://hil-speed.hetzner.com/100MB.bin",
                maxBytes = 1_048_576L,
                downloadBudgetMs = 3_000L,
                totalTimeoutMs = 4_000L,
            ),
            SpeedEndpoint(
                // Yandex's public package mirror is whitelisted on virtually every Russian carrier,
                // so even when cloudflare and hetzner are DPI-zeroed this endpoint still reports a
                // real throughput sample.
                url = "https://mirror.yandex.ru/ubuntu/ls-lR.gz",
                maxBytes = 1_048_576L,
                downloadBudgetMs = 3_000L,
                totalTimeoutMs = 4_000L,
            ),
        )
    }
}
