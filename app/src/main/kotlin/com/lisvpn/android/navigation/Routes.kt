package com.lisvpn.android.navigation

/**
 * Centralised string-constant routes. We intentionally avoid the type-safe Navigation APIs at MVP stage
 * to keep churn low while the navigation graph stabilises. Migration to `kotlinx.serialization`-based
 * type-safe routes is planned post-MVP.
 */
object LisRoute {
    const val Home = "home"
    const val Servers = "servers"
    const val Settings = "settings"
    const val Profiles = "profiles"
    const val SplitTunnel = "split-tunnel"
    const val Onboarding = "onboarding"
    const val Update = "update"
}
