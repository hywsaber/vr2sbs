package com.vr2sbs.tracking

import android.content.Context
import android.hardware.usb.UsbManager

/** USB vendor ID used by XREAL glasses (One / One Pro / Air). */
internal const val XREAL_USB_VENDOR_ID = 0x3318

/**
 * Returns whether an XREAL USB device is currently attached.
 *
 * The glasses always enumerate as a USB device while they are plugged in,
 * regardless of which Wi-Fi network the tablet uses. The USB Ethernet
 * function they expose is a separate, less reliable signal: Android only
 * provisions it on demand (see [LinkLocalEthernetProvisioner]), so the
 * control link can legitimately have no IPv4 address while the glasses are
 * attached and working.
 */
internal fun hasAttachedXrealUsbDevice(context: Context): Boolean {
    val usbManager = context.getSystemService(UsbManager::class.java) ?: return false
    return runCatching {
        usbManager.deviceList.values.any { device ->
            device.vendorId == XREAL_USB_VENDOR_ID
        }
    }.getOrDefault(false)
}
