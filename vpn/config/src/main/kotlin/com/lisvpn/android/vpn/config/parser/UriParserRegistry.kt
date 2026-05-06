package com.lisvpn.android.vpn.config.parser

import com.lisvpn.android.core.domain.model.Outbound
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of attempting to parse a single URI.
 */
sealed interface ParseResult {
    data class Ok(val outbound: Outbound, val displayName: String?, val rawUri: String) : ParseResult
    data class Failed(val rawUri: String, val reason: String) : ParseResult
}

/** Single-protocol parser. Implementations live in this package. */
fun interface UriParser {
    fun tryParse(uri: String): ParseResult?
}

/**
 * Composes all known [UriParser]s. The first one returning non-null wins.
 */
@Singleton
class UriParserRegistry @Inject constructor(
    private val vless: VlessUriParser,
    // TODO(MVP+1): VmessUriParser, TrojanUriParser, ShadowsocksUriParser
) {
    private val parsers: List<UriParser> = listOf(vless)

    fun parse(uri: String): ParseResult {
        for (parser in parsers) {
            val result = parser.tryParse(uri) ?: continue
            return result
        }
        return ParseResult.Failed(uri, "No parser handled the URI")
    }
}
