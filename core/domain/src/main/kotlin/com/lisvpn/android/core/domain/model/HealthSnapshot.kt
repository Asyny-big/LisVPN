package com.lisvpn.android.core.domain.model

import kotlinx.datetime.Instant

/**
 * Single-attempt probe result for a [Server]. Aggregated by [HealthScore] elsewhere.
 */
data class HealthSnapshot(
    val serverId: String,
    val timestamp: Instant,
    val tcpHandshakeMs: Int?,
    val tlsHandshakeMs: Int?,
    val httpRttMs: Int?,
    val success: Boolean,
    val networkType: NetworkType,
) {
    enum class NetworkType { Wifi, Cellular, Ethernet, VpnInterface, Unknown }
}

/** Aggregated rolling score over a window of [HealthSnapshot]s. */
data class HealthScore(
    val serverId: String,
    val score: Float,                 // 0.0 .. 1.0
    val avgPingMs: Int?,
    val successRate: Float,           // 0.0 .. 1.0
    val sampleCount: Int,
    val computedAt: Instant,
)
