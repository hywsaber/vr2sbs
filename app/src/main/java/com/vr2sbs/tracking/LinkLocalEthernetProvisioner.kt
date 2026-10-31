package com.vr2sbs.tracking

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.RequiresPermission
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Asks Android to bring up Ethernet for the glasses' USB network.
 *
 * Android provisions a USB Ethernet function on demand: while Wi-Fi is the
 * validated default network nothing requests Ethernet, so the glasses'
 * interface is left without an IPv4 address and the link-local control
 * addresses (169.254.1.1 / 169.254.2.1) are unreachable. Holding an Ethernet
 * network request makes the system start the Ethernet IpClient, which obtains
 * an IPv4 link-local address when no DHCP server answers.
 *
 * The request has to stay registered for as long as the head-tracking stream
 * runs, otherwise the system may tear the Ethernet network down again.
 * [release] drops it when the tracking session ends.
 */
class LinkLocalEthernetProvisioner(context: Context) {
    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Starts (or keeps) an Ethernet network request. Safe to call repeatedly. */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun requestEthernet() {
        if (callback != null) {
            return
        }
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val networkCallback = ConnectivityManager.NetworkCallback()
        val registered = runCatching {
            manager.requestNetwork(request, networkCallback)
        }
        if (registered.isSuccess) {
            callback = networkCallback
        }
    }

    /**
     * Waits until an Ethernet interface owns an IPv4 link-local address.
     *
     * Returns false when no such address appeared within [timeoutMs]. Callers
     * may still proceed: an Ethernet link with a DHCP-assigned address is just
     * as usable, and the host selection reads the real routes afterwards.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun awaitLinkLocalEthernetAddress(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = hasLinkLocalEthernetAddress()
        while (!found && System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            found = hasLinkLocalEthernetAddress()
        }
        found
    }

    fun release() {
        val pending = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(pending) }
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun hasLinkLocalEthernetAddress(): Boolean {
        val manager = connectivity ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                val isEthernet = capabilities
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                isEthernet && manager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.any { linkAddress ->
                        val address = linkAddress.address
                        address is Inet4Address && address.isLinkLocalAddress
                    } == true
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
