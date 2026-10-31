package com.vr2sbs.tracking

import java.net.InetAddress

internal const val DEFAULT_TRACKING_HOST = "169.254.2.1"
internal const val FALLBACK_TRACKING_HOST = "169.254.1.1"

/**
 * Chooses the glasses address that this device can actually reach.
 *
 * XREAL One exposes the same control and stream ports on two USB Ethernet
 * functions: NCM at 169.254.2.1 and ECM at 169.254.1.1. Android normally
 * configures the NCM link, so 169.254.2.1 is the default. Some devices (for
 * example the a tablet) only configure the ECM link, which leaves the
 * default address without a route while 169.254.1.1 works.
 *
 * [linkLocalRouteCidrs] holds the IPv4 routes of every network that owns an
 * IPv4 link-local address. The default address is returned whenever any of
 * those routes covers it, so devices whose calibration already works take the
 * exact same path as before. The fallback is only selected when the default
 * address is provably unroutable on all of them.
 */
internal fun selectTrackingHost(
    linkLocalRouteCidrs: List<String>,
    defaultHost: String = DEFAULT_TRACKING_HOST,
    fallbackHost: String = FALLBACK_TRACKING_HOST
): String {
    val defaultAddress = parseAddressLiteral(defaultHost) ?: return defaultHost
    if (linkLocalRouteCidrs.any { routeCoversHost(it, defaultAddress) }) {
        return defaultHost
    }
    val fallbackAddress = parseAddressLiteral(fallbackHost) ?: return defaultHost
    if (linkLocalRouteCidrs.any { routeCoversHost(it, fallbackAddress) }) {
        return fallbackHost
    }
    return defaultHost
}

/** Returns whether the IPv4/IPv6 route in CIDR form contains [host]. */
internal fun routeCoversHost(cidr: String, host: InetAddress): Boolean {
    val parts = cidr.split('/')
    if (parts.size != 2) return false
    val routeAddress = parseAddressLiteral(parts[0]) ?: return false
    val prefixLength = parts[1].toIntOrNull() ?: return false
    val routeBytes = routeAddress.address
    val hostBytes = host.address
    if (routeBytes.size != hostBytes.size) return false
    var remainingBits = prefixLength.coerceIn(0, routeBytes.size * 8)
    for (index in routeBytes.indices) {
        if (remainingBits <= 0) return true
        val mask = if (remainingBits >= 8) 0xFF else (0xFF shl (8 - remainingBits)) and 0xFF
        if ((routeBytes[index].toInt() and mask) != (hostBytes[index].toInt() and mask)) {
            return false
        }
        remainingBits -= 8
    }
    return true
}

/**
 * Parses an address literal only. Hostnames are rejected so this selection
 * path can never trigger a DNS lookup.
 */
private fun parseAddressLiteral(value: String): InetAddress? {
    if (!IPV4_LITERAL.matches(value) && !value.contains(':')) return null
    return runCatching { InetAddress.getByName(value) }.getOrNull()
}

private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
