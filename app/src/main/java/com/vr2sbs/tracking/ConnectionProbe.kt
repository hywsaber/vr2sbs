package com.vr2sbs.tracking

/**
 * Combines the routing view of the glasses control link with USB presence into
 * the connection state shown on the main screen.
 *
 * Either signal alone is enough. On some devices and Wi-Fi networks Android
 * leaves the USB Ethernet function unprovisioned, which means no network or
 * address candidate exists even though the glasses are attached and casting
 * through DisplayPort alt mode.
 */
internal fun evaluateConnectionProbe(
    networkCandidateInterfaces: List<String>,
    addressCandidateInterfaces: List<String>,
    usbAttached: Boolean
): OneXrConnectionProbe {
    val interfaces = networkCandidateInterfaces.distinct()
    val connected =
        interfaces.isNotEmpty() || addressCandidateInterfaces.isNotEmpty() || usbAttached
    val detail = when {
        interfaces.isNotEmpty() -> "connected via ${interfaces.joinToString(",")}"
        addressCandidateInterfaces.isNotEmpty() -> "connected"
        usbAttached -> "connected via USB"
        else -> "not connected"
    }
    return OneXrConnectionProbe(connected = connected, detail = detail)
}
