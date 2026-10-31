package com.vr2sbs.bilibili

data class BilibiliVideoStream(
    val qualityId: Int,
    val baseUrl: String,
    val bandwidth: Long,
    val codecs: String,
    val width: Int,
    val height: Int
)

data class BilibiliAudioStream(
    val id: Int,
    val baseUrl: String,
    val bandwidth: Long,
    val codecs: String
)

data class BilibiliDashStreams(
    val videos: List<BilibiliVideoStream>,
    val audios: List<BilibiliAudioStream>,
    val dolbyAudios: List<BilibiliAudioStream> = emptyList(),
    val flacAudios: List<BilibiliAudioStream> = emptyList()
)

data class BilibiliStreamSelection(
    val video: BilibiliVideoStream,
    val audio: BilibiliAudioStream?
) {
    val qualityLabel: String? get() = BilibiliQuality.label(video.qualityId)
}

object BilibiliQuality {
    fun label(qualityId: Int): String? = when (qualityId) {
        127 -> "8K"
        126 -> "Dolby Vision"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        6 -> "240P"
        else -> null
    }
}

object BilibiliStreamSelector {
    fun select(
        streams: BilibiliDashStreams,
        videoSupported: (codecs: String, width: Int, height: Int) -> Boolean = { _, _, _ -> true },
        audioSupported: (codecs: String) -> Boolean = { true }
    ): BilibiliStreamSelection? {
        val videos = streams.videos.filter { it.baseUrl.isNotBlank() }
        if (videos.isEmpty()) {
            return null
        }
        val supportedVideos = videos.filter {
            videoSupported(it.codecs, it.width, it.height)
        }
        // Fall back to every stream if codec probing found nothing. Playback may still
        // succeed when the codec list was incomplete or the device advertises aliases.
        val video = (supportedVideos.ifEmpty { videos }).sortedWith(
            compareByDescending<BilibiliVideoStream> { it.qualityId }
                .thenBy { videoCodecRank(it.codecs) }
                .thenByDescending { it.bandwidth }
        ).first()

        val allAudios = streams.audios + streams.dolbyAudios + streams.flacAudios
        val playableAudios = allAudios.filter {
            it.baseUrl.isNotBlank() && audioSupported(it.codecs)
        }
        val audio = (playableAudios.ifEmpty { allAudios.filter { it.baseUrl.isNotBlank() } })
            .sortedWith(
                compareBy<BilibiliAudioStream> { audioCodecRank(it.codecs) }
                    .thenByDescending { it.bandwidth }
            )
            .firstOrNull()

        return BilibiliStreamSelection(video = video, audio = audio)
    }

    private fun videoCodecRank(codecs: String): Int = when {
        codecs.startsWith("avc", ignoreCase = true) -> 0
        codecs.startsWith("hev", ignoreCase = true) ||
            codecs.startsWith("hvc", ignoreCase = true) -> 1
        codecs.startsWith("av01", ignoreCase = true) -> 2
        codecs.startsWith("vp09", ignoreCase = true) -> 3
        else -> 4
    }

    private fun audioCodecRank(codecs: String): Int = when {
        codecs.startsWith("mp4a", ignoreCase = true) -> 0
        codecs.startsWith("flac", ignoreCase = true) -> 1
        codecs.startsWith("ec-3", ignoreCase = true) -> 2
        codecs.startsWith("ac-4", ignoreCase = true) -> 3
        else -> 4
    }
}
