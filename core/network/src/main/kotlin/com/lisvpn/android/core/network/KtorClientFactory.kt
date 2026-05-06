package com.lisvpn.android.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import timber.log.Timber

/**
 * Constructs a single [HttpClient] used app-wide. Honours [NetworkConfig.strictPinning] —
 * dev flavor relaxes pinning so 3x-ui staging works without ceremony, while prod enforces the
 * supplied [NetworkConfig.certificatePins].
 *
 * Important: streaming subscription bodies (`/sub/<token>`) tend to be small (tens of KB) so
 * we use the OkHttp engine which handles redirects, gzip, IPv6 and timeouts gracefully.
 */
@Singleton
class KtorClientFactory @Inject constructor() {

    fun create(config: NetworkConfig): HttpClient {
        val pinner = if (config.strictPinning && config.certificatePins.isNotEmpty()) {
            val builder = CertificatePinner.Builder()
            for (pin in config.certificatePins) {
                builder.add(pin.hostname, pin.sha256Pin)
            }
            builder.build()
        } else CertificatePinner.DEFAULT

        return HttpClient(OkHttp) {
            engine {
                config {
                    certificatePinner(pinner)
                    retryOnConnectionFailure(true)
                    followRedirects(true)
                }
            }

            install(ContentNegotiation) {
                json(LisJson)
            }
            install(UserAgent) {
                agent = config.defaultUserAgent
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            if (config.enableHttpLogging) {
                install(Logging) {
                    level = LogLevel.INFO
                    logger = object : Logger {
                        override fun log(message: String) {
                            Timber.tag("ktor").d(maskSensitiveNetworkLog(message))
                        }
                    }
                    sanitizeHeader { name ->
                        name.equals("Authorization", ignoreCase = true) ||
                            name.equals("Cookie", ignoreCase = true) ||
                            name.equals("Set-Cookie", ignoreCase = true) ||
                            name.equals("User-Agent", ignoreCase = true)
                    }
                }
            }

            defaultRequest {
                // Stable User-Agent ensures the LisVPN backend's device fingerprinting treats
                // this device as a single identity (see DEVICE_LIMITS.md in the backend repo).
                headers.append("Accept", "*/*")
            }

            expectSuccess = false // we map status manually in repositories
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 30_000L
        val SUB_URL_REGEX = Regex("https?://[^\\s]+/sub/[^\\s]+")
        val TOKEN_QUERY_REGEX = Regex("([?&](?:sub|token)=)[^\\s&]+", RegexOption.IGNORE_CASE)

        fun maskSensitiveNetworkLog(message: String): String =
            message
                .replace(SUB_URL_REGEX) { match -> "${match.value.substringBefore("/sub/")}/sub/<masked>" }
                .replace(TOKEN_QUERY_REGEX) { match -> "${match.groupValues[1]}<masked>" }
    }
}

internal val LisJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
    coerceInputValues = true
}
