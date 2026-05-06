package com.lisvpn.android.core.domain.model

import kotlinx.datetime.Instant

/**
 * A profile is a logical group of [Server]s sourced from the same origin (LisVPN subscription,
 * pasted URL, QR scan, etc.). Profiles can refresh; servers cannot.
 */
data class Profile(
    val id: String,
    val name: String,
    val source: ProfileSource,
    val expiresAt: Instant?,
    val updateIntervalHours: Int?,
    val announceMessage: String?,
    val createdAt: Instant,
    val lastRefreshedAt: Instant?,
    val isPrimary: Boolean,
)

sealed interface ProfileSource {
    /** Standard subscription URL (sing-box / Hiddify text format). */
    data class SubscriptionUrl(val url: String) : ProfileSource

    /** Single VLESS/VMess/Trojan/SS URI pasted manually or from QR. */
    data class SingleUri(val uri: String) : ProfileSource

    /** Imported from raw sing-box JSON (advanced mode). */
    data class JsonConfig(val origin: String) : ProfileSource

    /** Reserved for future LisVPN authenticated path. */
    data class LisVpnAccount(val accountId: String) : ProfileSource
}
