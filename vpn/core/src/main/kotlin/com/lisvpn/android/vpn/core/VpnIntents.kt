package com.lisvpn.android.vpn.core

/** Stable Intent contract between the controller and [LisVpnService]. */
object VpnIntents {
    const val ACTION_START = "com.lisvpn.android.vpn.action.START"
    const val ACTION_STOP = "com.lisvpn.android.vpn.action.STOP"
    const val ACTION_RECONNECT = "com.lisvpn.android.vpn.action.RECONNECT"
    const val ACTION_SELECT_OUTBOUND = "com.lisvpn.android.vpn.action.SELECT_OUTBOUND"

    /** UTF-8 sing-box JSON payload. */
    const val EXTRA_CONFIG_JSON = "com.lisvpn.android.vpn.extra.CONFIG_JSON"
    /** Optional human-readable label of the chosen server / group (shown in notification). */
    const val EXTRA_SERVER_LABEL = "com.lisvpn.android.vpn.extra.SERVER_LABEL"
    const val EXTRA_OUTBOUND_GROUP = "com.lisvpn.android.vpn.extra.OUTBOUND_GROUP"
    const val EXTRA_OUTBOUND_TAG = "com.lisvpn.android.vpn.extra.OUTBOUND_TAG"
}
