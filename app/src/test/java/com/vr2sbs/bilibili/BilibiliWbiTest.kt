package com.vr2sbs.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliWbiTest {
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `builds the reference mixin key`() {
        assertEquals(
            "ea1db124af3c7062474693fa704f4ff8",
            BilibiliWbi.mixinKey(imgKey, subKey)
        )
    }

    @Test
    fun `matches the documented signature vector`() {
        val signed = BilibiliWbi.sign(
            params = mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
            imgKey = imgKey,
            subKey = subKey,
            timestampSeconds = 1702204169L
        )

        assertEquals(
            "bar=514&foo=114&wts=1702204169&zab=1919810" +
                "&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            signed.query
        )
        assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4", signed.wRid)
    }

    @Test
    fun `encodes values like encodeURIComponent`() {
        assertEquals("one%20one%20four", BilibiliWbi.encode("one one four"))
        assertEquals(
            "%E4%BA%94%E4%B8%80%E5%9B%9B",
            BilibiliWbi.encode("五一四")
        )
        assertEquals("BV1Fd4y1N78r", BilibiliWbi.encode("BV1Fd4y1N78r"))
    }

    @Test
    fun `filters characters excluded by the WBI algorithm`() {
        val signed = BilibiliWbi.sign(
            params = mapOf("key" to "a!'()*b"),
            imgKey = imgKey,
            subKey = subKey,
            timestampSeconds = 1702204169L
        )

        assertTrue(signed.query.startsWith("key=ab&wts=1702204169"))
        assertFalse(signed.query.contains("%21"))
        assertFalse(signed.query.contains("%2A"))
    }

    @Test
    fun `extracts the key filename from a WBI image URL`() {
        assertEquals(
            imgKey,
            BilibiliWbi.extractKeyFromUrl(
                "https://i0.hdslb.com/bfs/wbi/$imgKey.png"
            )
        )
    }
}
