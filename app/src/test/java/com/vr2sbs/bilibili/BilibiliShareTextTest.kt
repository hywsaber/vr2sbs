package com.vr2sbs.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliShareTextTest {
    @Test
    fun `extracts the video from a full Bilibili share text`() {
        val text = "[VR180 5.7K] VR视角带逛北大教学楼（关掉B站全景模式可正常播放） " +
            "https://www.bilibili.com/video/BV1Fd4y1N78r/"

        val link = BilibiliShareText.extract(text)

        assertNotNull(link)
        assertEquals("BV1Fd4y1N78r", link?.bvid)
        assertEquals("https://www.bilibili.com/video/BV1Fd4y1N78r", link?.url)
        assertEquals(1, link?.page)
        assertFalse(link?.shortLink ?: true)
    }

    @Test
    fun `keeps the page number from a multi-part share link`() {
        val link = BilibiliShareText.extract(
            "https://www.bilibili.com/video/BV1Fd4y1N78r/?p=2&share_source=copy_web"
        )

        assertNotNull(link)
        assertEquals(2, link?.page)
        assertEquals("https://www.bilibili.com/video/BV1Fd4y1N78r/?p=2", link?.url)
    }

    @Test
    fun `strips trailing Chinese punctuation from the URL`() {
        val link = BilibiliShareText.extract(
            "看这个 https://www.bilibili.com/video/BV1Fd4y1N78r/。"
        )

        assertEquals("BV1Fd4y1N78r", link?.bvid)
    }

    @Test
    fun `recognizes short links for later redirect resolution`() {
        val link = BilibiliShareText.extract("https://b23.tv/AbCdEf")

        assertNotNull(link)
        assertTrue(link?.shortLink ?: false)
        assertNull(link?.bvid)
    }

    @Test
    fun `stops a short link before trailing Chinese text`() {
        val link = BilibiliShareText.extract("打开链接 https://b23.tv/AbCdEf复制此链接")

        assertEquals("https://b23.tv/AbCdEf", link?.url)
        assertTrue(link?.shortLink ?: false)
    }

    @Test
    fun `recognizes av video links`() {
        val link = BilibiliShareText.extract(
            "https://m.bilibili.com/video/av170001/?p=2"
        )

        assertNotNull(link)
        assertEquals(170001L, link?.avid)
        assertEquals(2, link?.page)
        assertNull(link?.bvid)
    }

    @Test
    fun `recognizes a bare BV id`() {
        val link = BilibiliShareText.extract("BV1Fd4y1N78r")

        assertEquals("BV1Fd4y1N78r", link?.bvid)
        assertEquals("https://www.bilibili.com/video/BV1Fd4y1N78r", link?.url)
    }

    @Test
    fun `ignores non-Bilibili links`() {
        assertNull(BilibiliShareText.extract("https://example.com/video/BV1Fd4y1N78r"))
        assertNull(BilibiliShareText.extract("https://example.com/watch?v=123"))
        assertNull(BilibiliShareText.extract("no video here"))
    }

    @Test
    fun `picks the Bilibili link when other links are present`() {
        val link = BilibiliShareText.extract(
            "Source: https://example.com/article and video: " +
                "https://www.bilibili.com/video/BV1Fd4y1N78r"
        )

        assertEquals("BV1Fd4y1N78r", link?.bvid)
    }

    @Test
    fun `does not treat a non-video Bilibili page as a video link`() {
        assertNull(BilibiliShareText.extract("https://www.bilibili.com/read/cv123456"))
        assertFalse(BilibiliShareText.isBilibiliUrl("https://www.bilibili.com/read/cv123456"))
    }
}
