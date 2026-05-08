package com.lisvpn.android.core.domain.model

/**
 * Whether a server should be offered as a general full-tunnel destination.
 *
 * Historically this filter excluded any server whose *display name* contained "Telegram",
 * assuming the entry was scoped to a single service. In practice, subscription providers
 * use these labels purely as marketing ("optimised for Telegram") even though the underlying
 * VLESS / Reality outbound is a perfectly normal full-tunnel server. The label-based filter
 * was therefore hiding usable servers from the manual list and from the AUTO bootstrap
 * candidate set without any actual technical reason. We now treat every parsed server as a
 * general candidate; if a future provider really ships a service-scoped entry we'll detect it
 * from the outbound's routing config, not the display name.
 */
@Suppress("unused")
fun Server.isGeneralVpnEligible(): Boolean = specialPurposeReason() == null

@Suppress("unused", "UnusedReceiverParameter")
fun Server.specialPurposeReason(): String? = null
