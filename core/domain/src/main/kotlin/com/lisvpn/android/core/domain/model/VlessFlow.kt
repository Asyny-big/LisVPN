package com.lisvpn.android.core.domain.model

private const val VLESS_FLOW_VISION = "xtls-rprx-vision"
private const val VLESS_FLOW_VISION_UDP443 = "xtls-rprx-vision-udp443"

fun normalizeVlessFlowForSingBox(value: String?): String? {
    val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when (normalized) {
        VLESS_FLOW_VISION,
        VLESS_FLOW_VISION_UDP443 -> VLESS_FLOW_VISION
        "none", "off", "false" -> null
        else -> null
    }
}

fun isVlessFlowSupportedByCurrentLibbox(value: String?): Boolean =
    value.isNullOrBlank() || normalizeVlessFlowForSingBox(value) != null
