package com.lisvpn.android.deeplink

import android.net.Uri
import com.lisvpn.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of parsing an incoming deep link.
 * The app only acts on [ImportSubscription]; everything else is ignored.
 */
sealed interface DeepLinkResult {

    /**
     * Imports a subscription URL into the profile manager. Source can be either:
     *  - `lisvpn://import?sub=<encoded subscription URL>`
     *  - `https://<flavor host>/c/<token>` — token is composed into the canonical
     *    `<BACKEND_BASE_URL>/sub/<token>` URL.
     */
    data class ImportSubscription(val subscriptionUrl: String, val source: Source) : DeepLinkResult {
        enum class Source { CUSTOM_SCHEME, APP_LINK }
    }
}

@Singleton
class DeepLinkParser @Inject constructor() {

    fun parse(uri: Uri): DeepLinkResult? {
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            CUSTOM_SCHEME -> parseCustomScheme(uri)
            "https" -> parseAppLink(uri)
            else -> null
        }
    }

    private fun parseCustomScheme(uri: Uri): DeepLinkResult? {
        if (uri.host?.equals(IMPORT_HOST, ignoreCase = true) != true) return null
        val sub = uri.getQueryParameter(QUERY_SUB)?.takeIf { it.isNotBlank() } ?: return null
        val canonical = when {
            sub.startsWith("https://", ignoreCase = true) -> sub
            sub.startsWith("http://", ignoreCase = true) -> return null
            else -> "${BuildConfig.BACKEND_BASE_URL.trimEnd('/')}/sub/${Uri.encode(sub)}"
        }
        return DeepLinkResult.ImportSubscription(
            subscriptionUrl = canonical,
            source = DeepLinkResult.ImportSubscription.Source.CUSTOM_SCHEME,
        )
    }

    private fun parseAppLink(uri: Uri): DeepLinkResult? {
        val host = uri.host?.lowercase() ?: return null
        if (host !in trustedAppLinkHosts) return null

        val segments = uri.pathSegments
        if (segments.size < 2) return null
        if (!segments[0].equals(CONNECT_PATH, ignoreCase = true)) return null

        val token = segments[1].takeIf { it.isNotBlank() } ?: return null
        val canonical = "${BuildConfig.BACKEND_BASE_URL.trimEnd('/')}/sub/${Uri.encode(token)}"
        return DeepLinkResult.ImportSubscription(
            subscriptionUrl = canonical,
            source = DeepLinkResult.ImportSubscription.Source.APP_LINK,
        )
    }

    private val trustedAppLinkHosts: Set<String> = buildSet {
        add(BuildConfig.DEEP_LINK_HOST.lowercase())
        // Both flavors trust both hosts as a graceful fallback for cross-environment links.
        add("lisvpn.ru")
        add("govchat.ru")
    }

    private companion object {
        const val CUSTOM_SCHEME = "lisvpn"
        const val IMPORT_HOST = "import"
        const val QUERY_SUB = "sub"
        const val CONNECT_PATH = "c"
    }
}
