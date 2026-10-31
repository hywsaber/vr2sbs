package com.vr2sbs.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliStreamSelectorTest {
    @Test
    fun `selects the highest available quality`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(
                video(80, "avc1.640028", 1920, 1080, 2_000_000),
                video(120, "hev1.1.6.L150", 3840, 2160, 8_000_000),
                video(127, "av01.0.00M.10", 7680, 4320, 20_000_000)
            )
        )

        assertEquals(127, selection?.video?.qualityId)
        assertEquals("8K", selection?.qualityLabel)
    }

    @Test
    fun `skips qualities the device cannot decode`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(
                video(80, "avc1.640028", 1920, 1080, 2_000_000),
                video(120, "hev1.1.6.L150", 3840, 2160, 8_000_000),
                video(127, "av01.0.00M.10", 7680, 4320, 20_000_000)
            ),
            videoSupported = { codecs, _, _ -> !codecs.startsWith("av01") }
        )

        assertEquals(120, selection?.video?.qualityId)
        assertEquals("4K", selection?.qualityLabel)
    }

    @Test
    fun `falls back to the highest stream when no codec is advertised`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(
                video(80, "avc1.640028", 1920, 1080, 2_000_000),
                video(127, "av01.0.00M.10", 7680, 4320, 20_000_000)
            ),
            videoSupported = { _, _, _ -> false }
        )

        assertEquals(127, selection?.video?.qualityId)
    }

    @Test
    fun `prefers the safest codec within the same quality`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(
                video(120, "hev1.1.6.L150", 3840, 2160, 9_000_000),
                video(120, "avc1.640033", 3840, 2160, 12_000_000)
            )
        )

        assertEquals("avc1.640033", selection?.video?.codecs)
    }

    @Test
    fun `prefers standard AAC audio over higher-bandwidth alternatives`() {
        val selection = BilibiliStreamSelector.select(
            streams = BilibiliDashStreams(
                videos = listOf(video(80, "avc1.640028", 1920, 1080, 2_000_000)),
                audios = listOf(
                    audio(30216, "mp4a.40.2", 64_000),
                    audio(30280, "mp4a.40.2", 192_000)
                ),
                dolbyAudios = listOf(audio(30250, "ec-3", 448_000)),
                flacAudios = listOf(audio(30251, "flac", 1_200_000))
            )
        )

        assertEquals(30280, selection?.audio?.id)
    }

    @Test
    fun `uses a non-AAC audio stream when it is the only supported one`() {
        val selection = BilibiliStreamSelector.select(
            streams = BilibiliDashStreams(
                videos = listOf(video(80, "avc1.640028", 1920, 1080, 2_000_000)),
                audios = emptyList(),
                dolbyAudios = listOf(audio(30250, "ec-3", 448_000))
            ),
            audioSupported = { it == "ec-3" }
        )

        assertEquals(30250, selection?.audio?.id)
    }

    @Test
    fun `returns a video-only selection when there is no audio`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(video(80, "avc1.640028", 1920, 1080, 2_000_000))
        )

        assertEquals(80, selection?.video?.qualityId)
        assertNull(selection?.audio)
    }

    @Test
    fun `returns null when there are no video streams`() {
        assertNull(
            BilibiliStreamSelector.select(
                streams = BilibiliDashStreams(videos = emptyList(), audios = emptyList())
            )
        )
    }

    @Test
    fun `ignores blank stream URLs`() {
        val selection = BilibiliStreamSelector.select(
            streams = streams(
                video(127, "av01.0.00M.10", 7680, 4320, 20_000_000, url = ""),
                video(80, "avc1.640028", 1920, 1080, 2_000_000)
            )
        )

        assertEquals(80, selection?.video?.qualityId)
    }

    @Test
    fun `uses a short quality label for every known quality`() {
        assertTrue(BilibiliQuality.label(112) == "1080P+")
        assertTrue(BilibiliQuality.label(64) == "720P")
        assertNull(BilibiliQuality.label(9999))
    }

    private fun streams(vararg videos: BilibiliVideoStream): BilibiliDashStreams =
        BilibiliDashStreams(videos = videos.toList(), audios = emptyList())

    private fun video(
        qualityId: Int,
        codecs: String,
        width: Int,
        height: Int,
        bandwidth: Long,
        url: String = "https://example.com/$qualityId.m4s"
    ) = BilibiliVideoStream(
        qualityId = qualityId,
        baseUrl = url,
        bandwidth = bandwidth,
        codecs = codecs,
        width = width,
        height = height
    )

    private fun audio(id: Int, codecs: String, bandwidth: Long) = BilibiliAudioStream(
        id = id,
        baseUrl = "https://example.com/$id.m4s",
        bandwidth = bandwidth,
        codecs = codecs
    )
}
