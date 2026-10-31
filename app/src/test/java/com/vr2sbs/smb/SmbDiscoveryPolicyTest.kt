package com.vr2sbs.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SmbDiscoveryPolicyTest {
    @Test
    fun scansSlash24SubnetExcludingNetworkAndBroadcast() {
        val targets = ipv4ScanTargets(ipv4("198.51.100.42"), 24)

        assertEquals(254, targets.size)
        assertEquals("198.51.100.1", targets.first().hostAddress)
        assertEquals("198.51.100.254", targets.last().hostAddress)
        assertTrue(targets.any { it.hostAddress == "198.51.100.42" })
        assertFalse(targets.any { it.hostAddress == "198.51.100.0" })
        assertFalse(targets.any { it.hostAddress == "198.51.100.255" })
    }

    @Test
    fun scansSmallerSubnetExactly() {
        val targets = ipv4ScanTargets(ipv4("198.51.100.42"), 28).map { it.hostAddress }

        assertEquals((33..46).map { "198.51.100.$it" }, targets)
    }

    @Test
    fun limitsLargeSubnetsToTheLocalSlash24() {
        val targets = ipv4ScanTargets(ipv4("203.0.113.3"), 16)

        assertEquals(254, targets.size)
        assertEquals("203.0.113.1", targets.first().hostAddress)
        assertEquals("203.0.113.254", targets.last().hostAddress)
    }

    @Test
    fun scansSlash23WhenItFitsTheBudget() {
        val targets = ipv4ScanTargets(ipv4("198.51.100.7"), 23)

        assertEquals(510, targets.size)
        assertEquals("198.51.100.1", targets.first().hostAddress)
        assertEquals("198.51.101.254", targets.last().hostAddress)
    }

    @Test
    fun fallsBackToSlash24ForPointToPointPrefixes() {
        val targets = ipv4ScanTargets(ipv4("198.51.100.42"), 31)

        assertEquals(254, targets.size)
        assertEquals("198.51.100.1", targets.first().hostAddress)
        assertEquals("198.51.100.254", targets.last().hostAddress)
    }

    @Test
    fun netBiosQueryUsesWildcardNodeStatusName() {
        val query = SmbNetBios.buildNodeStatusQuery(transactionId = 0x1234)

        assertEquals(50, query.size)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(0x20, query[12].toInt() and 0xFF)
        assertEquals("CK", String(query.copyOfRange(13, 15), Charsets.US_ASCII))
        assertTrue(query.copyOfRange(15, 45).all { it == 'A'.code.toByte() })
        assertEquals(0, query[45].toInt())
        assertEquals(0x00, query[46].toInt() and 0xFF)
        assertEquals(0x21, query[47].toInt() and 0xFF)
        assertEquals(0x00, query[48].toInt() and 0xFF)
        assertEquals(0x01, query[49].toInt() and 0xFF)
    }

    @Test
    fun parsesNodeStatusResponseAndPrefersUniqueWorkstationName() {
        val response = buildNodeStatusResponse(
            listOf(
                NodeNameBytes("WORKGROUP", 0x00, 0x8000),
                NodeNameBytes("MEDIA-PC", 0x00, 0x0000),
                NodeNameBytes("MEDIA-PC", 0x20, 0x0000)
            )
        )

        assertEquals(
            "MEDIA-PC",
            SmbNetBios.parseNodeStatusResponse(response, response.size)
        )
    }

    @Test
    fun ignoresGroupDomainNameWhenUniqueNameExists() {
        val response = buildNodeStatusResponse(
            listOf(
                NodeNameBytes("WORKGROUP", 0x00, 0x8000),
                NodeNameBytes("NAS", 0x20, 0x0000)
            )
        )

        assertEquals("NAS", SmbNetBios.parseNodeStatusResponse(response, response.size))
    }

    @Test
    fun prefersHostLevelProfileForRememberedDevice() {
        val hostLevel = SmbProfile.create("nas.local", "", "", "neo", "host")
        val shareLevel = SmbProfile.create("nas.local", "media", "", "neo", "share")

        val remembered = rememberedDeviceProfiles(listOf(shareLevel, hostLevel))

        assertEquals(1, remembered.size)
        assertEquals(hostLevel.id, remembered.single().id)
        assertEquals("", remembered.single().share)
    }

    @Test
    fun derivesHostProfileFromSavedShareAccount() {
        val shareLevel = SmbProfile.create("nas.local", "media", "WORKGROUP", "neo", "secret")

        val remembered = rememberedDeviceProfiles(listOf(shareLevel)).single()

        assertEquals("nas.local", remembered.host)
        assertEquals("", remembered.share)
        assertEquals("WORKGROUP", remembered.domain)
        assertEquals("neo", remembered.username)
        assertEquals("secret", remembered.password)
    }

    @Test
    fun keepsDifferentHostsSeparateInRememberedDevices() {
        val first = SmbProfile.create("nas.local", "", "", "neo", "one")
        val second = SmbProfile.create("desktop.local", "", "", "neo", "two")

        val remembered = rememberedDeviceProfiles(listOf(first, second))

        assertEquals(2, remembered.size)
        assertEquals(
            listOf("desktop.local", "nas.local"),
            remembered.map { it.host }
        )
    }

    @Test
    fun deviceProfileUsesHostRootUriWithoutShare() {
        val profile = SmbProfile.create("nas.local", "", "", "neo", "secret")

        assertEquals("smb://nas.local/", profile.rootUri)
        assertEquals("neo@nas.local", profile.displayName)
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address

    private fun buildNodeStatusResponse(names: List<NodeNameBytes>): ByteArray {
        val encodedWildcard = ByteArray(32)
        encodedWildcard[0] = 'C'.code.toByte()
        encodedWildcard[1] = 'K'.code.toByte()
        for (index in 2 until 32) encodedWildcard[index] = 'A'.code.toByte()

        val rdataLength = 1 + names.size * 18
        val buffer = ByteBuffer
            .allocate(12 + 1 + 32 + 1 + 4 + 2 + 10 + rdataLength)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(0x1234)
        buffer.putShort(0x8400.toShort())
        buffer.putShort(1) // questions
        buffer.putShort(1) // answers
        buffer.putShort(0) // authority records
        buffer.putShort(0) // additional records
        buffer.put(0x20)
        buffer.put(encodedWildcard)
        buffer.put(0)
        buffer.putShort(0x0021)
        buffer.putShort(1)
        // Answer: compression pointer to the question name, NBSTAT type, IN class.
        buffer.putShort(0xC00C.toShort())
        buffer.putShort(0x0021)
        buffer.putShort(1)
        buffer.putInt(0) // ttl
        buffer.putShort(rdataLength.toShort())
        buffer.put(names.size.toByte())
        names.forEach { node ->
            buffer.put(node.name.padEnd(15, ' ').take(15).toByteArray(Charsets.US_ASCII))
            buffer.put(node.suffix.toByte())
            buffer.putShort(node.flags.toShort())
        }
        return buffer.array()
    }

    private data class NodeNameBytes(
        val name: String,
        val suffix: Int,
        val flags: Int
    )
}
