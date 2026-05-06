package com.lisvpn.android.core.domain.model

/**
 * Per-app routing policy. Enforced at the Android VpnService layer
 * (`addAllowedApplication` / `addDisallowedApplication`), NOT inside sing-box rules,
 * because sing-box `process_name` rules are unreliable in TUN mode on Android.
 */
data class AppRules(
    val mode: Mode,
    val packages: Set<String>,
) {
    enum class Mode {
        /** All apps go through VPN (default consumer experience). */
        Off,

        /** Only [packages] are tunnelled. Everything else bypasses VPN. */
        AllowList,

        /** All apps tunnelled EXCEPT [packages]. Useful for excluding banks, Yandex, etc. */
        DisallowList,
    }

    companion object {
        val Default = AppRules(mode = Mode.Off, packages = emptySet())
    }
}
