package com.lisvpn.android.core.common.logging

import android.util.Log
import timber.log.Timber

/**
 * Production-aware Timber tree.
 *
 * In debug builds: logs everything to logcat with stack origin tag.
 * In release builds: drops VERBOSE/DEBUG, masks high-cardinality fields,
 * and forwards WARN+ to logcat (no remote sinks until telemetry feature lands).
 */
class LisLogTree(private val isDebug: Boolean) : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        if (isDebug) true else priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeMessage = if (isDebug) message else maskSensitive(message)
        val safeTag = tag ?: DEFAULT_TAG
        val safeStack = t?.let {
            val stack = Log.getStackTraceString(it)
            if (isDebug) stack else maskSensitive(stack)
        }
        if (safeStack != null) Log.println(priority, safeTag, "$safeMessage\n$safeStack")
        else Log.println(priority, safeTag, safeMessage)
    }

    private fun maskSensitive(message: String): String =
        message
            .replace(UUID_REGEX, "<uuid>")
            .replace(VLESS_URI_REGEX, "vless://<masked>")
            .replace(TOKEN_REGEX, "<token>")
            .replace(IPV4_REGEX, "<ip>")
            .replace(SUB_URL_REGEX) { match ->
                val prefix = match.value.substringBefore("/sub/")
                "$prefix/sub/<masked>"
            }

    private companion object {
        const val DEFAULT_TAG = "LisVPN"
        val UUID_REGEX = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        val VLESS_URI_REGEX = Regex("vless://[^\\s]+", RegexOption.IGNORE_CASE)
        val TOKEN_REGEX = Regex("\\b[A-Za-z0-9_-]{24,}\\b")
        val IPV4_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        val SUB_URL_REGEX = Regex("https?://[^\\s]+/sub/[^\\s]+")
    }
}
