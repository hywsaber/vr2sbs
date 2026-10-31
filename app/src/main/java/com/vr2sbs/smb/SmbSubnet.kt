package com.vr2sbs.smb

import java.net.Inet4Address
import java.net.InetAddress

internal const val DEFAULT_MAX_SCAN_HOSTS = 512

private const val IPV4_MAX = 0xFFFFFFFFL
private const val FALLBACK_PREFIX_LENGTH = 24

/**
 * Returns the usable IPv4 hosts to probe for SMB.
 *
 * A typical home network is a /24, which yields 254 addresses. Larger subnets are
 * narrowed to the /24 around [address] so a scan cannot turn into a 65k-host sweep;
 * smaller subnets are probed exactly. Point-to-point and single-host prefixes also
 * fall back to the local /24.
 */
internal fun ipv4ScanTargets(
    address: Inet4Address,
    prefixLength: Int,
    maxHosts: Int = DEFAULT_MAX_SCAN_HOSTS
): List<Inet4Address> {
    require(maxHosts > 0) { "maxHosts must be positive" }

    val addressValue = address.toUnsignedLong()
    var prefix = prefixLength.coerceIn(0, 32)
    if (prefix == 0 || prefix >= 31) {
        prefix = FALLBACK_PREFIX_LENGTH
    }

    var mask = ipv4Mask(prefix)
    var network = addressValue and mask
    var broadcast = network or (mask.inv() and IPV4_MAX)
    var hostCount = (broadcast - network - 1).coerceAtLeast(0)

    if (hostCount > maxHosts) {
        prefix = FALLBACK_PREFIX_LENGTH
        mask = ipv4Mask(prefix)
        network = addressValue and mask
        broadcast = network or (mask.inv() and IPV4_MAX)
        hostCount = (broadcast - network - 1).coerceAtLeast(0)
    }

    if (hostCount <= 0) return emptyList()

    val count = hostCount.coerceAtMost(maxHosts.toLong()).toInt()
    val first = network + 1
    return List(count) { offset -> ipv4Address(first + offset) }
}

private fun ipv4Mask(prefixLength: Int): Long =
    if (prefixLength == 0) 0L else (IPV4_MAX shl (32 - prefixLength)) and IPV4_MAX

private fun Inet4Address.toUnsignedLong(): Long =
    address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }

private fun ipv4Address(value: Long): Inet4Address =
    InetAddress.getByAddress(
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    ) as Inet4Address
