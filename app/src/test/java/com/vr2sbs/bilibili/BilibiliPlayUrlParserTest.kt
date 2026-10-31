package com.vr2sbs.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BilibiliPlayUrlParserTest {
    @Test
    fun `parses DASH video and audio streams`() {
        val streams = BilibiliPlayUrlParser.parse(DASH_RESPONSE)

        assertEquals(3, streams.videos.size)
        assertEquals(127, streams.videos[2].qualityId)
        assertEquals(7680, streams.videos[2].width)
        assertEquals(4320, streams.videos[2].height)
        assertEquals("https://example.com/8k.m4s", streams.videos[2].baseUrl)
        assertEquals(2, streams.audios.size)
        assertEquals("mp4a.40.2", streams.audios[0].codecs)
    }

    @Test
    fun `parses Dolby and FLAC audio`() {
        val streams = BilibiliPlayUrlParser.parse(
            """
            {
              "code": 0,
              "data": {
                "dash": {
                  "video": [
                    {"id": 80, "baseUrl": "https://example.com/v.m4s",
                     "bandwidth": 1, "codecs": "avc1.640028", "width": 1920, "height": 1080}
                  ],
                  "audio": [],
                  "dolby": {"audio": [
                    {"id": 30250, "baseUrl": "https://example.com/dolby.m4s",
                     "bandwidth": 448000, "codecs": "ec-3"}
                  ]},
                  "flac": {"audio": {"id": 30251, "baseUrl": "https://example.com/flac.m4s",
                     "bandwidth": 1200000, "codecs": "flac"}}
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, streams.dolbyAudios.size)
        assertEquals(1, streams.flacAudios.size)
        assertEquals("ec-3", streams.dolbyAudios[0].codecs)
        assertEquals("flac", streams.flacAudios[0].codecs)
    }

    @Test
    fun `falls back to a progressive durl response`() {
        val streams = BilibiliPlayUrlParser.parse(
            """
            {
              "code": 0,
              "data": {
                "quality": 16,
                "durl": [{"url": "https://example.com/video.flv"}]
              }
            }
            """.trimIndent()
        )

        assertEquals(1, streams.videos.size)
        assertEquals("https://example.com/video.flv", streams.videos[0].baseUrl)
        assertTrue(streams.audios.isEmpty())
    }

    @Test
    fun `surfaces API errors`() {
        try {
            BilibiliPlayUrlParser.parse(
                """{"code": -101, "message": "账号未登录", "data": null}"""
            )
            fail("Expected BilibiliApiException")
        } catch (error: BilibiliApiException) {
            assertEquals(-101, error.code)
            assertEquals("账号未登录", error.message)
        }
    }

    @Test
    fun `parses video info pages`() {
        val info = BilibiliPlayUrlParser.parseVideoInfo(VIDEO_INFO_RESPONSE)

        assertEquals("BV1Fd4y1N78r", info.bvid)
        assertEquals(123L, info.avid)
        assertEquals(456L, info.cidForPage(1))
        assertEquals(789L, info.cidForPage(2))
        assertEquals(505L, info.durationSeconds)
    }

    @Test
    fun `returns no cid for an out of range multi-page request`() {
        val info = BilibiliPlayUrlParser.parseVideoInfo(VIDEO_INFO_RESPONSE)

        assertEquals(0L, info.cidForPage(99))
    }

    @Test
    fun `rejects a missing stream list`() {
        try {
            BilibiliPlayUrlParser.parse("""{"code": 0, "data": {}}""")
            fail("Expected BilibiliApiException")
        } catch (error: BilibiliApiException) {
            assertTrue(error.message.orEmpty().contains("No playable streams"))
        }
    }

    @Test
    fun `video info without pages uses the default cid`() {
        val info = BilibiliPlayUrlParser.parseVideoInfo(
            """
            {"code": 0, "data": {"bvid": "BV1Fd4y1N78r", "aid": 1, "cid": 456, "title": "t"}}
            """.trimIndent()
        )

        assertEquals(456L, info.cidForPage(1))
        assertEquals(0L, info.cidForPage(2))
        assertEquals("BV1Fd4y1N78r", info.bvid)
    }

    private companion object {
        val DASH_RESPONSE = """
            {
              "code": 0,
              "message": "OK",
              "data": {
                "quality": 127,
                "dash": {
                  "duration": 505.2,
                  "video": [
                    {"id": 80, "baseUrl": "https://example.com/1080.m4s",
                     "bandwidth": 2000000, "codecs": "avc1.640028", "width": 1920, "height": 1080},
                    {"id": 120, "base_url": "https://example.com/4k.m4s",
                     "bandwidth": 8000000, "codecs": "hev1.1.6.L150", "width": 3840, "height": 2160},
                    {"id": 127, "baseUrl": "https://example.com/8k.m4s",
                     "bandwidth": 20000000, "codecs": "av01.0.00M.10", "width": 7680, "height": 4320}
                  ],
                  "audio": [
                    {"id": 30280, "baseUrl": "https://example.com/a192.m4s",
                     "bandwidth": 192000, "codecs": "mp4a.40.2"},
                    {"id": 30216, "baseUrl": "https://example.com/a64.m4s",
                     "bandwidth": 64000, "codecs": "mp4a.40.2"}
                  ]
                }
              }
            }
        """.trimIndent()

        val VIDEO_INFO_RESPONSE = """
            {
              "code": 0,
              "data": {
                "bvid": "BV1Fd4y1N78r",
                "aid": 123,
                "cid": 456,
                "title": "VR180 test",
                "duration": 505,
                "pages": [
                  {"page": 1, "cid": 456, "part": "P1"},
                  {"page": 2, "cid": 789, "part": "P2"}
                ]
              }
            }
        """.trimIndent()
    }
}
