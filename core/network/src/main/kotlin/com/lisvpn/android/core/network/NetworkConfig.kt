package com.lisvpn.android.core.network

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

/**
 * Flavor-supplied configuration. The `:app` module provides values from BuildConfig so
 * `:core:network` itself stays free of generated build classes.
 */
data class NetworkConfig(
    val backendBaseUrl: String,
    val defaultUserAgent: String,
    val strictPinning: Boolean,
    val certificatePins: List<CertificatePin>,
    val enableHttpLogging: Boolean,
) {
    data class CertificatePin(
        val hostname: String,
        /** Sha256 fingerprint of the SubjectPublicKeyInfo, formatted as `sha256/<base64>`. */
        val sha256Pin: String,
    )

    companion object {
        val DEV_DEFAULT = NetworkConfig(
            backendBaseUrl = "https://govchat.ru",
            defaultUserAgent = "LisVPN-Android/0.1.0-dev (sing-box)",
            strictPinning = false,
            certificatePins = emptyList(),
            enableHttpLogging = true,
        )
    }
}

@Qualifier @Retention(BINARY) annotation class LisHttpClient
