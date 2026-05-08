package com.lisvpn.android.core.domain.model

/**
 * Some subscription entries are intentionally scoped to a single service (for example Telegram).
 * The app currently starts a full-device/general VPN tunnel, so those entries must not be used as
 * AUTO candidates or manual "all internet" servers.
 */
fun Server.isGeneralVpnEligible(): Boolean = specialPurposeReason() == null

fun Server.specialPurposeReason(): String? {
    val label = displayName.lowercase()
    return when {
        label.contains("telegram") || label.contains("телеграм") -> "Telegram-only"
        else -> null
    }
}
