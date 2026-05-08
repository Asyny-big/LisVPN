package com.lisvpn.android.vpn.health

import com.lisvpn.android.core.domain.model.Server

data class TaggedServer(
    val server: Server,
    val outboundTag: String,
)

data class SmartNetworkProfile(
    val networkClass: SmartNetworkClass,
    val fingerprint: String,
    val mobileOperator: String?,
    val asn: String?,
) {
    val networkKey: String = "${networkClass.name.lowercase()}:$fingerprint"
    val isMobileLike: Boolean
        get() = networkClass == SmartNetworkClass.Mobile || networkClass == SmartNetworkClass.Metered
}

enum class SmartNetworkClass {
    Wifi,
    Mobile,
    Ethernet,
    Metered,
    Unknown,
}

data class SmartServerHistory(
    val serverId: String,
    val lastScore: Double,
    val successCount: Int,
    val failureCount: Int,
    val lastLatencyMs: Int?,
    val lastThroughputKbps: Long?,
    val lastJitterMs: Int?,
    val lastPacketLoss: Double,
    val telegramReachable: Boolean,
    val youtubeReachable: Boolean,
    val lastSuccessAtMs: Long?,
) {
    val sampleCount: Int get() = successCount + failureCount
    val successRate: Double
        get() = if (sampleCount == 0) 0.0 else successCount.toDouble() / sampleCount.toDouble()
}

data class FastProbeResult(
    val taggedServer: TaggedServer,
    val dnsMs: Int?,
    val tcpMs: Int?,
    val tlsMs: Int?,
    val proxyHandshakeMs: Int?,
    val success: Boolean,
    val failureReason: String?,
) {
    val latencyMs: Int?
        get() = proxyHandshakeMs ?: tlsMs ?: tcpMs
}

data class AutoSelectionCandidate(
    val taggedServer: TaggedServer,
    val fastProbe: FastProbeResult,
    val history: SmartServerHistory?,
) {
    val server: Server get() = taggedServer.server
    val outboundTag: String get() = taggedServer.outboundTag
}

data class ValidationEndpointResult(
    val name: String,
    val url: String,
    val success: Boolean,
    val httpCode: Int?,
    val elapsedMs: Int?,
    val error: String?,
)

data class TunnelValidationResult(
    val serverId: String,
    val vpnNetworkSeen: Boolean,
    val endpointResults: List<ValidationEndpointResult>,
    val dnsWorks: Boolean,
    val eligible: Boolean,
    val elapsedMs: Int,
    val failureReason: String?,
) {
    val successCount: Int get() = endpointResults.count { it.success }
    val checkCount: Int get() = endpointResults.size
    val averageRttMs: Int?
        get() = endpointResults.mapNotNull { result ->
            result.elapsedMs.takeIf { result.success }
        }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
    val jitterMs: Int?
        get() {
            val samples = endpointResults.mapNotNull { result ->
                result.elapsedMs.takeIf { result.success }
            }
            if (samples.size < 2) return null
            return samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }
                .average()
                .toInt()
        }
    val packetLossApprox: Double
        get() = if (checkCount == 0) 1.0 else 1.0 - (successCount.toDouble() / checkCount.toDouble())
    val telegramReachable: Boolean get() = endpointResults.any { it.name == "telegram" && it.success }
    val youtubeReachable: Boolean get() = endpointResults.any { it.name == "youtube" && it.success }

    companion object {
        fun failed(serverId: String, reason: String, elapsedMs: Int = 0): TunnelValidationResult =
            TunnelValidationResult(
                serverId = serverId,
                vpnNetworkSeen = false,
                endpointResults = emptyList(),
                dnsWorks = false,
                eligible = false,
                elapsedMs = elapsedMs,
                failureReason = reason,
            )
    }
}

data class ThroughputResult(
    val success: Boolean,
    val bytesRead: Long,
    val elapsedMs: Int?,
    val firstByteMs: Int?,
    val mbps: Double?,
    val error: String?,
) {
    val kbps: Long?
        get() = mbps?.let { (it * 1_000.0).toLong() }
}

data class ScoredAutoServer(
    val candidate: AutoSelectionCandidate,
    val validation: TunnelValidationResult,
    val throughput: ThroughputResult?,
    val score: Double,
) {
    val server: Server get() = candidate.server
    val outboundTag: String get() = candidate.outboundTag
    val eligible: Boolean get() = validation.eligible
}

data class HealthCheckResult(
    val healthy: Boolean,
    val elapsedMs: Int?,
    val reason: String?,
)
