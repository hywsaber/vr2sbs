package com.vr2sbs.tracking

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHostSelectionTest {
    @Test
    fun keepsTheDefaultHostWhenItsSubnetIsRouted() {
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("169.254.2.0/24"))
        )
    }

    @Test
    fun keepsTheDefaultHostWhenBothLinksAreRouted() {
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("169.254.1.0/24", "169.254.2.0/24"))
        )
    }

    @Test
    fun keepsTheDefaultHostForWideLinkLocalRoutes() {
        // APIPA addresses can carry a /16, which covers the default host.
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("169.254.0.0/16"))
        )
    }

    @Test
    fun keepsTheDefaultHostWhenADefaultRouteExists() {
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("0.0.0.0/0", "169.254.1.0/24"))
        )
    }

    @Test
    fun fallsBackWhenOnlyTheEcmLinkIsRouted() {
        // ECM-only device: eth0 owns 169.254.1.x, the NCM link is unmanaged.
        assertEquals(
            FALLBACK_TRACKING_HOST,
            selectTrackingHost(listOf("169.254.1.0/24"))
        )
    }

    @Test
    fun fallsBackOnlyIfTheFallbackAddressIsCovered() {
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("203.0.113.0/24", "198.51.100.0/24"))
        )
        assertEquals(
            DEFAULT_TRACKING_HOST,
            selectTrackingHost(listOf("169.254.2.128/25", "169.254.1.128/25"))
        )
    }

    @Test
    fun keepsTheDefaultHostWithoutLinkLocalRoutes() {
        assertEquals(DEFAULT_TRACKING_HOST, selectTrackingHost(emptyList()))
    }

    @Test
    fun respectsCustomAddresses() {
        assertEquals(
            "198.51.100.5",
            selectTrackingHost(
                listOf("198.51.100.0/24"),
                defaultHost = "198.51.100.5",
                fallbackHost = "203.0.113.1"
            )
        )
        assertEquals(
            "203.0.113.1",
            selectTrackingHost(
                listOf("203.0.113.0/24"),
                defaultHost = "198.51.100.5",
                fallbackHost = "203.0.113.1"
            )
        )
    }

    @Test
    fun routeCoverageHandlesPrefixBoundaries() {
        val host = InetAddress.getByName("169.254.2.1")
        assertTrue(routeCoversHost("169.254.2.0/24", host))
        assertTrue(routeCoversHost("169.254.0.0/16", host))
        assertTrue(routeCoversHost("0.0.0.0/0", host))
        assertTrue(routeCoversHost("169.254.2.0/32", InetAddress.getByName("169.254.2.0")))
        assertFalse(routeCoversHost("169.254.2.0/32", host))
        assertFalse(routeCoversHost("169.254.1.0/24", host))
        assertFalse(routeCoversHost("fe80::/64", host))
        assertFalse(routeCoversHost("not-a-route", host))
    }
}
