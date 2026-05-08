package com.lisvpn.android.vpn.core

import com.lisvpn.android.core.domain.model.AppRules
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.vpn.config.SingBoxConfigBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin adapter so [VpnConnectionController] does not depend on `:vpn:config` directly.
 * Centralising config assembly here also makes it trivial to A/B different builders later.
 */
@Singleton
class VpnConfigAssembler @Inject constructor(
    private val builder: SingBoxConfigBuilder,
) {
    fun assemble(
        servers: List<Server>,
        smartSelection: Boolean,
        appRules: AppRules,
    ): String = builder.build(servers = servers, smartSelection = smartSelection, appRules = appRules)

    /**
     * Builds the headless config that powers the AUTO mode pre-VPN speed test (no TUN, only the
     * SOCKS5 mixed inbound). See [SingBoxConfigBuilder.buildPreflight] for details.
     */
    fun assemblePreflight(
        servers: List<Server>,
        appRules: AppRules,
    ): String = builder.buildPreflight(servers = servers, appRules = appRules)
}
