package com.lisvpn.android.core.domain.model

import kotlinx.datetime.Instant

sealed interface VpnState {

    data object Idle : VpnState

    data object Preparing : VpnState

    data class Connecting(val serverDisplayName: String?) : VpnState

    data class Connected(
        val server: ConnectedServer,
        val connectedAt: Instant,
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val pingMs: Int? = null,
    ) : VpnState

    data class Reconnecting(val attempt: Int, val previousServerDisplayName: String?) : VpnState

    data object Disconnecting : VpnState

    data class Error(val reason: Reason, val detail: String? = null) : VpnState

    enum class Reason {
        PermissionDenied,        // user cancelled VpnService.prepare()
        PermissionRevoked,       // OS revoked while running
        NoProfile,               // no servers in active profile
        ConfigInvalid,           // failed to assemble sing-box JSON or libbox rejected it
        TunnelEstablishFailed,   // VpnService.Builder.establish() returned null
        StartFailed,             // BoxService.start() threw
        SubscriptionExpired,     // backend signalled expiry via Subscription-Userinfo or empty body
        DeviceLimitReached,      // backend "announce" header semantically maps here
        NetworkUnavailable,
        Unknown,
    }
}

data class ConnectedServer(
    val serverId: String,
    val displayName: String,
    val countryCode: String?,
)
