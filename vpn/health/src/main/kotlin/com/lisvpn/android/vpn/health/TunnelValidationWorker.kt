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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class TunnelValidationWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun validate(serverId: String): TunnelValidationResult =
        // AUTO mode used to require ALL targets to succeed. That broke on Russian mobile networks
        // where DPI/whitelisting frequently blocks one of cloudflare/1.1.1.1/youtube even when the
        // server itself is fully usable — the server was wrongly marked ineligible and never got
        // a speed test. We now require DNS + at least AUTO_MIN_SUCCESSFUL_TARGETS targets to pass,
        // out of a wider, more DPI-diverse probe set (cloudflare, google, telegram, yandex, mail).
        validateTargets(
            serverId = serverId,
            targets = VALIDATION_TARGETS,
            requireAllTargets = false,
            minSuccessfulTargets = AUTO_MIN_SUCCESSFUL_TARGETS,
        )

    suspend fun validateManual(serverId: String): TunnelValidationResult =
        // Manual mode should prove the tunnel carries real traffic, not reject a user-picked server
        // because one public probe endpoint (for example Cloudflare 204) is blocked or slow.
        validateTargets(
            serverId = serverId,
            targets = MANUAL_VALIDATION_TARGETS,
            requireAllTargets = false,
            minSuccessfulTargets = 1,
        )

    private suspend fun validateTargets(
        serverId: String,
        targets: List<ValidationTarget>,
        requireAllTargets: Boolean,
        minSuccessfulTargets: Int = 1,
    ): TunnelValidationResult = withContext(ioDispatcher) {
        val startedAt = SystemClock.elapsedRealtime()
        val network = waitForVpnNetwork() ?: return@withContext TunnelValidationResult.failed(
            serverId = serverId,
            reason = "vpn network not visible",
            elapsedMs = elapsedSince(startedAt),
        )
        val dnsWorks = withTimeoutOrNull(DNS_TIMEOUT_MS) {
            DNS_VALIDATION_HOSTS.any { host ->
                runCatching { network.getAllByName(host).isNotEmpty() }.getOrDefault(false)
            }
        } == true

        val checks = coroutineScope {
            targets.map { target ->
                async {
                    withTimeoutOrNull(HTTP_CHECK_TIMEOUT_MS) {
                        checkEndpoint(network, target)
                    } ?: ValidationEndpointResult(
                        name = target.name,
                        url = target.url,
                        success = false,
                        httpCode = null,
                        elapsedMs = null,
                        error = "timeout",
                    )
                }
            }.awaitAll()
        }
        val successfulCount = checks.count { it.success }
        val allHttpOk = checks.isNotEmpty() && checks.all { it.success }
        val anyHttpOk = checks.any { it.success }
        val httpEligible = when {
            requireAllTargets -> allHttpOk
            minSuccessfulTargets > 1 -> successfulCount >= minSuccessfulTargets
            else -> anyHttpOk
        }
        // Do not hard-fail solely on one synthetic DNS host. If HTTPS URL checks succeeded, DNS
        // through the VPN worked for real traffic even if a carrier/DPI blocks one validation host.
        val eligible = httpEligible && (dnsWorks || anyHttpOk)
        TunnelValidationResult(
            serverId = serverId,
            vpnNetworkSeen = true,
            endpointResults = checks,
            dnsWorks = dnsWorks,
            eligible = eligible,
            elapsedMs = elapsedSince(startedAt),
            failureReason = if (eligible) null else buildFailureReason(dnsWorks, checks),
        )
    }

    suspend fun quickGenerate204(): HealthCheckResult = withContext(ioDispatcher) {
        val network = findVpnNetwork() ?: return@withContext HealthCheckResult(
            healthy = false,
            elapsedMs = null,
            reason = "vpn network not visible",
        )
        val checks = coroutineScope {
            HEALTH_TARGETS.map { target ->
                async {
                    withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                        checkEndpoint(network, target)
                    } ?: ValidationEndpointResult(
                        name = target.name,
                        url = target.url,
                        success = false,
                        httpCode = null,
                        elapsedMs = null,
                        error = "timeout",
                    )
                }
            }.awaitAll()
        }
        val best = checks.filter { it.success }.minByOrNull { it.elapsedMs ?: Int.MAX_VALUE }
        HealthCheckResult(
            healthy = best != null,
            elapsedMs = best?.elapsedMs,
            reason = best?.error ?: if (best != null) null else checks.joinToString(limit = 2) { "${it.name}:${it.error ?: it.httpCode}" },
        )
    }

    private suspend fun waitForVpnNetwork(): Network? {
        repeat(VPN_NETWORK_WAIT_ATTEMPTS) {
            findVpnNetwork()?.let { return it }
            delay(VPN_NETWORK_WAIT_STEP_MS)
        }
        return null
    }

    private fun findVpnNetwork(): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun checkEndpoint(network: Network, target: ValidationTarget): ValidationEndpointResult {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val connection = (network.openConnection(URL(target.url)) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (target.readBody && code in 200..299) {
                    BufferedInputStream(connection.inputStream).use { input ->
                        val buffer = ByteArray(256)
                        input.read(buffer)
                    }
                }
                val success = target.accepts(code)
                ValidationEndpointResult(
                    name = target.name,
                    url = target.url,
                    success = success,
                    httpCode = code,
                    elapsedMs = elapsedSince(startedAt),
                    error = if (success) null else "http $code",
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            ValidationEndpointResult(
                name = target.name,
                url = target.url,
                success = false,
                httpCode = null,
                elapsedMs = elapsedSince(startedAt),
                error = "${err::class.java.simpleName}: ${err.message.orEmpty()}".take(96),
            )
        }
    }

    private fun buildFailureReason(
        dnsWorks: Boolean,
        checks: List<ValidationEndpointResult>,
    ): String {
        if (!dnsWorks) return "dns validation failed"
        val failed = checks.filterNot { it.success }
        return failed.joinToString(limit = 3) { "${it.name}:${it.error ?: it.httpCode}" }
            .ifBlank { "internet validation failed" }
    }

    private fun elapsedSince(startedAt: Long): Int =
        (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)

    private data class ValidationTarget(
        val name: String,
        val url: String,
        val readBody: Boolean = false,
        val accepts: (Int) -> Boolean = { it in 200..299 },
    )

    private companion object {
        const val USER_AGENT = "LisVPN/TunnelValidation"
        val DNS_VALIDATION_HOSTS = listOf("telegram.org", "yandex.ru", "www.gstatic.com")
        const val DNS_TIMEOUT_MS = 1_500L
        const val HTTP_CHECK_TIMEOUT_MS = 2_400L
        const val HEALTH_CHECK_TIMEOUT_MS = 2_000L
        const val HTTP_CONNECT_TIMEOUT_MS = 1_500
        const val HTTP_READ_TIMEOUT_MS = 1_800
        const val VPN_NETWORK_WAIT_ATTEMPTS = 8
        const val VPN_NETWORK_WAIT_STEP_MS = 150L

        val HEALTH_TARGET = ValidationTarget(
            name = "cloudflare204",
            url = "https://cp.cloudflare.com/generate_204",
            accepts = { it == 204 || it in 200..299 },
        )
        val GOOGLE_TARGET = ValidationTarget(
            name = "google204",
            url = "https://www.google.com/generate_204",
            accepts = { it == 204 || it in 200..399 },
        )
        val TELEGRAM_TARGET = ValidationTarget(
            name = "telegram",
            url = "https://telegram.org",
            accepts = { it in 200..399 },
        )
        val YANDEX_TARGET = ValidationTarget(
            name = "yandex",
            url = "https://yandex.ru/favicon.ico",
            accepts = { it in 200..399 },
        )
        val HEALTH_TARGETS = listOf(HEALTH_TARGET, GOOGLE_TARGET, YANDEX_TARGET)
        val MANUAL_VALIDATION_TARGETS = listOf(
            HEALTH_TARGET,
            TELEGRAM_TARGET,
            YANDEX_TARGET,
        )
        // Wider, DPI-diverse target set so AUTO validation does not fail entirely when one or two
        // of the targets are blocked by a carrier whitelist. We mix big-tech (cloudflare/google) and
        // RU-friendly endpoints (yandex/mail) so at least 2 of these always answer on any working
        // server, no matter which side of the whitelist the user is on.
        val VALIDATION_TARGETS = listOf(
            HEALTH_TARGET,
            ValidationTarget(
                name = "cloudflareTrace",
                url = "https://1.1.1.1/cdn-cgi/trace",
                readBody = true,
            ),
            GOOGLE_TARGET,
            TELEGRAM_TARGET,
            ValidationTarget(
                name = "youtube",
                url = "https://www.youtube.com/generate_204",
                accepts = { it == 204 || it in 200..399 },
            ),
            YANDEX_TARGET,
            ValidationTarget(
                name = "mailru",
                url = "https://mail.ru/favicon.ico",
                accepts = { it in 200..399 },
            ),
        )
        // Out of the diverse target set we only need 2 successes to consider the tunnel usable.
        // This survives a single DPI block (e.g. only cloudflare is blocked) without dropping a
        // healthy server.
        const val AUTO_MIN_SUCCESSFUL_TARGETS = 2
    }
}
