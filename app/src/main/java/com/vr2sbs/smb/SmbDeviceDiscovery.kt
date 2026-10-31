package com.vr2sbs.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

data class SmbDevice(
    val host: String,
    val name: String = ""
) {
    val displayName: String
        get() = name.ifBlank { host }
}

class SmbDiscoveryUnavailableException :
    IOException("No Wi-Fi or Ethernet IPv4 network is available")

/**
 * Discovers SMB servers on the local network by probing TCP port 445 (the port
 * every SMB2/SMB3 file server listens on) and resolving NetBIOS names.
 *
 * Discovery emits devices as they are found, so the UI can populate progressively.
 */
class SmbDeviceDiscovery(context: Context) {
    private val appContext = context.applicationContext

    fun scan(): Flow<SmbDevice> = channelFlow {
        val localNetworks = localIpv4Networks(appContext)
        if (localNetworks.isEmpty()) throw SmbDiscoveryUnavailableException()

        val localAddresses = localNetworks
            .mapTo(mutableSetOf()) { it.address.hostAddress.orEmpty() }
        val targets = linkedSetOf<Inet4Address>()
        localNetworks.forEach { network ->
            ipv4ScanTargets(network.address, network.prefixLength)
                .asSequence()
                .filterNot { address ->
                    address.hostAddress?.let { it in localAddresses } == true
                }
                .take((MAX_SCAN_TARGETS - targets.size).coerceAtLeast(0))
                .forEach { targets += it }
        }
        if (targets.isEmpty()) throw SmbDiscoveryUnavailableException()

        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val emitted = Collections.synchronizedSet(mutableSetOf<String>())
        coroutineScope {
            targets.forEach { target ->
                launch(Dispatchers.IO) {
                    val reachable = semaphore.withPermit { isSmbReachable(target) }
                    if (!reachable) return@launch
                    val host = target.hostAddress ?: return@launch
                    if (!emitted.add(host)) return@launch
                    val name = SmbNetBios.queryName(target).orEmpty()
                    send(SmbDevice(host = host, name = name))
                }
            }
        }
    }

    private fun isSmbReachable(host: Inet4Address): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, SMB_PORT), CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

    // allNetworks is deprecated but remains the simplest way to find the LAN even
    // when a VPN is the active network.
    @Suppress("DEPRECATION")
    private fun localIpv4Networks(context: Context): List<LocalIpv4Network> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val candidates = manager.allNetworks.flatMap { network ->
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return@flatMap emptyList<LocalIpv4Network>()
            val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) return@flatMap emptyList<LocalIpv4Network>()
            val linkProperties = manager.getLinkProperties(network)
                ?: return@flatMap emptyList<LocalIpv4Network>()
            linkProperties.linkAddresses.mapNotNull { linkAddress ->
                linkAddress.toLocalIpv4Network()
            }
        }
        if (candidates.isNotEmpty()) {
            return candidates.distinctBy { it.address.hostAddress to it.prefixLength }
        }

        val activeNetwork = manager.activeNetwork ?: return emptyList()
        val linkProperties = manager.getLinkProperties(activeNetwork) ?: return emptyList()
        return linkProperties.linkAddresses
            .mapNotNull { it.toLocalIpv4Network() }
            .distinctBy { it.address.hostAddress to it.prefixLength }
    }

    private fun android.net.LinkAddress.toLocalIpv4Network(): LocalIpv4Network? {
        val ipv4 = address as? Inet4Address ?: return null
        if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return null
        return LocalIpv4Network(ipv4, prefixLength)
    }

    private data class LocalIpv4Network(
        val address: Inet4Address,
        val prefixLength: Int
    )

    private companion object {
        const val SMB_PORT = 445
        const val CONNECT_TIMEOUT_MS = 450
        const val SCAN_CONCURRENCY = 48
        const val MAX_SCAN_TARGETS = 1024
    }
}
