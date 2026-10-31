package com.vr2sbs.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProbeTest {
    @Test
    fun reportsNetworkCandidatesAsConnected() {
        val probe = evaluateConnectionProbe(
            networkCandidateInterfaces = listOf("eth0"),
            addressCandidateInterfaces = emptyList(),
            usbAttached = false
        )

        assertTrue(probe.connected)
        assertEquals("connected via eth0", probe.detail)
    }

    @Test
    fun reportsAddressCandidatesAsConnected() {
        val probe = evaluateConnectionProbe(
            networkCandidateInterfaces = emptyList(),
            addressCandidateInterfaces = listOf("eth0"),
            usbAttached = false
        )

        assertTrue(probe.connected)
    }

    @Test
    fun reportsUsbPresenceAsConnectedWithoutProvisionedNetwork() {
        // USB-only case: the glasses are attached and casting, but Android has
        // not provisioned the USB Ethernet function, so neither routing view
        // lists the link.
        val probe = evaluateConnectionProbe(
            networkCandidateInterfaces = emptyList(),
            addressCandidateInterfaces = emptyList(),
            usbAttached = true
        )

        assertTrue(probe.connected)
        assertEquals("connected via USB", probe.detail)
    }

    @Test
    fun reportsNotConnectedWithoutAnySignal() {
        val probe = evaluateConnectionProbe(
            networkCandidateInterfaces = emptyList(),
            addressCandidateInterfaces = emptyList(),
            usbAttached = false
        )

        assertFalse(probe.connected)
        assertEquals("not connected", probe.detail)
    }

    @Test
    fun prefersTheProvisionedInterfaceInTheDetail() {
        val probe = evaluateConnectionProbe(
            networkCandidateInterfaces = listOf("eth0", "eth0"),
            addressCandidateInterfaces = listOf("eth0"),
            usbAttached = true
        )

        assertTrue(probe.connected)
        assertEquals("connected via eth0", probe.detail)
    }
}
