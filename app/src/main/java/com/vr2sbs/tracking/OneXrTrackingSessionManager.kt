package com.vr2sbs.tracking

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.RequiresPermission
import io.onexr.OneXrClient
import io.onexr.OneXrEndpoint
import io.onexr.XrBiasState
import io.onexr.XrConnectionInfo
import io.onexr.XrPoseDataMode
import io.onexr.XrPoseSnapshot
import io.onexr.XrSessionState
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

data class OneXrConnectionProbe(
    val connected: Boolean,
    val detail: String
)

class OneXrTrackingSessionManager(
    context: Context,
    private val endpoint: OneXrEndpoint = OneXrEndpoint()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeHost: String = endpoint.host
    private var client: OneXrClient = createClient(endpoint)
    private val ethernetProvisioner = LinkLocalEthernetProvisioner(appContext)

    private val _pose = MutableStateFlow<PoseState?>(null)
    private val _sessionState = MutableStateFlow(client.sessionState.value)
    private val _biasState = MutableStateFlow(client.biasState.value)

    val pose: StateFlow<PoseState?> = _pose.asStateFlow()
    val sessionState: StateFlow<XrSessionState> = _sessionState.asStateFlow()
    val biasState: StateFlow<XrBiasState> = _biasState.asStateFlow()

    private var poseJob: Job? = null
    private var sessionJob: Job? = null
    private var biasJob: Job? = null

    init {
        startCollectors(client)
    }

    private fun createClient(endpoint: OneXrEndpoint): OneXrClient {
        return OneXrClient(appContext, endpoint).also { created ->
            created.setPoseDataMode(XrPoseDataMode.SMOOTH_IMU)
        }
    }

    private fun startCollectors(target: OneXrClient) {
        poseJob = scope.launch {
            target.poseData.collect { snapshot ->
                _pose.value = snapshot?.toPoseState()
            }
        }
        sessionJob = scope.launch {
            target.sessionState.collect { state -> _sessionState.value = state }
        }
        biasJob = scope.launch {
            target.biasState.collect { state -> _biasState.value = state }
        }
    }

    private suspend fun stopCollectors() {
        poseJob?.cancelAndJoin()
        sessionJob?.cancelAndJoin()
        biasJob?.cancelAndJoin()
        poseJob = null
        sessionJob = null
        biasJob = null
    }

    /**
     * Switches to the glasses' alternative Ethernet link only when the default
     * address has no route on this device. When the default address is
     * routable (the normal case) this is a no-op and the existing client is
     * kept unchanged.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun ensureReachableHost() {
        val selectedHost = resolveTrackingHost()
        if (selectedHost == activeHost) {
            return
        }
        val replacement = createClient(endpoint.copy(host = selectedHost))
        stopCollectors()
        val previous = client
        client = replacement
        activeHost = selectedHost
        _pose.value = null
        _sessionState.value = replacement.sessionState.value
        _biasState.value = replacement.biasState.value
        startCollectors(replacement)
        runCatching { previous.stop() }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun resolveTrackingHost(): String {
        if (endpoint.host != DEFAULT_TRACKING_HOST) {
            return endpoint.host
        }
        val routes = runCatching { linkLocalRouteCidrs() }.getOrElse { return endpoint.host }
        return selectTrackingHost(routes)
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun linkLocalRouteCidrs(): List<String> {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val routes = mutableListOf<String>()
        for (network in connectivity.allNetworks) {
            val linkProperties = connectivity.getLinkProperties(network) ?: continue
            val hasIpv4LinkLocalAddress = linkProperties.linkAddresses.any { linkAddress ->
                linkAddress.address is Inet4Address && linkAddress.address.isLinkLocalAddress
            }
            if (!hasIpv4LinkLocalAddress) {
                continue
            }
            for (route in linkProperties.routes) {
                val destination = route.destination ?: continue
                val address = destination.address
                if (address !is Inet4Address) {
                    continue
                }
                routes += "${address.hostAddress}/${destination.prefixLength}"
            }
        }
        return routes
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun probeConnection(): OneXrConnectionProbe {
        // The USB check is a binder call; keep it off the caller's (usually
        // main) thread since this probe runs every couple of seconds.
        val usbAttached = withContext(Dispatchers.IO) {
            hasAttachedXrealUsbDevice(appContext)
        }
        return runCatching {
            val routing = client.describeRouting()
            evaluateConnectionProbe(
                networkCandidateInterfaces = routing.networkCandidates.map { it.interfaceName },
                addressCandidateInterfaces = routing.addressCandidates.map { it.interfaceName },
                usbAttached = usbAttached
            )
        }.getOrElse { error ->
            if (usbAttached) {
                OneXrConnectionProbe(connected = true, detail = "connected via USB")
            } else {
                OneXrConnectionProbe(
                    connected = false,
                    detail = "error ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun runCalibration(): Result<XrConnectionInfo> {
        // Make sure the glasses' USB Ethernet function is provisioned before
        // the host is selected, otherwise there is no route to 169.254.x.x.
        ethernetProvisioner.requestEthernet()
        ethernetProvisioner.awaitLinkLocalEthernetAddress()
        return runCatching {
            ensureReachableHost()
            client.start()
        }
    }

    suspend fun stop(): Result<Unit> {
        ethernetProvisioner.release()
        return runCatching { client.stop() }
    }

    suspend fun zeroView(): Result<Unit> {
        return runCatching { client.zeroView() }
    }

    suspend fun recalibrate(): Result<Unit> {
        return runCatching { client.recalibrate() }
    }

}

private fun XrPoseSnapshot.toPoseState(): PoseState {
    val yawRad = relativeOrientation.yaw.toRadians()
    val pitchRad = relativeOrientation.pitch.toRadians()
    val rollRad = relativeOrientation.roll.toRadians()
    val quaternion = eulerToQuaternion(yawRad, pitchRad, rollRad)
    return PoseState(
        yaw = yawRad,
        pitch = pitchRad,
        roll = rollRad,
        qx = quaternion.first,
        qy = quaternion.second,
        qz = quaternion.third,
        qw = quaternion.fourth,
        trackingAvailable = isCalibrated
    )
}

private fun Float.toRadians(): Float {
    return Math.toRadians(toDouble()).toFloat()
}

private fun eulerToQuaternion(yaw: Float, pitch: Float, roll: Float): Quaternion {
    val cy = cos(yaw * 0.5f)
    val sy = sin(yaw * 0.5f)
    val cp = cos(pitch * 0.5f)
    val sp = sin(pitch * 0.5f)
    val cr = cos(roll * 0.5f)
    val sr = sin(roll * 0.5f)
    val qw = (cr * cp * cy) + (sr * sp * sy)
    val qx = (sr * cp * cy) - (cr * sp * sy)
    val qy = (cr * sp * cy) + (sr * cp * sy)
    val qz = (cr * cp * sy) - (sr * sp * cy)
    return Quaternion(qx, qy, qz, qw)
}

private data class Quaternion(
    val first: Float,
    val second: Float,
    val third: Float,
    val fourth: Float
)
