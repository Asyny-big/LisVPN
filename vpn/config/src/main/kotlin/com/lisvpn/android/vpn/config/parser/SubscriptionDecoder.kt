package com.lisvpn.android.vpn.config.parser

import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes the body of a subscription endpoint (e.g. `GET /sub/<token>` from the LisVPN backend).
 *
 * Handles:
 *  - Plain text with one URI per line (the LisVPN format).
 *  - Base64 (URL-safe or standard) encoded plain text — common in v2ray/Hiddify ecosystems.
 *  - Mixed content with `# comment` lines — those are ignored.
 *
 * Returns the raw URI lines; protocol-specific parsing is delegated to [UriParserRegistry].
 */
@Singleton
class SubscriptionDecoder @Inject constructor() {

    fun decode(rawBody: String): List<String> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()

        val candidate = if (looksLikeBase64(trimmed)) {
            decodeBase64(trimmed)
                ?.takeIf { it.contains("://") }
                ?: trimmed
        } else trimmed

        return candidate.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .filter { it.contains("://") }
            .toList()
    }

    private fun looksLikeBase64(text: String): Boolean {
        if (text.contains("://")) return false
        val sample = text.take(SAMPLE)
        if (sample.length < 12) return false
        return sample.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' || it.isWhitespace() }
    }

    private fun decodeBase64(text: String): String? {
        val flags = intArrayOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flag in flags) {
            val decoded = runCatching { String(Base64.decode(text, flag), Charsets.UTF_8) }.getOrNull()
            if (decoded != null) return decoded
        }
        return null
    }

    private companion object { const val SAMPLE = 64 }
}
