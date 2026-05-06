package com.lisvpn.android.vpn.core

/** Stable Intent contract between the controller and [LisVpnService]. */
object VpnIntents {
    const val ACTION_START = "com.lisvpn.android.vpn.action.START"
    const val ACTION_STOP = "com.lisvpn.android.vpn.action.STOP"
    const val ACTION_RECONNECT = "com.lisvpn.android.vpn.action.RECONNECT"

    /** UTF-8 sing-box JSON payload. */
    const val EXTRA_CONFIG_JSON = "com.lisvpn.android.vpn.extra.CONFIG_JSON"
    /** Optional human-readable label of the chosen server / group (shown in notification). */
    const val EXTRA_SERVER_LABEL = "com.lisvpn.android.vpn.extra.SERVER_LABEL"
}
