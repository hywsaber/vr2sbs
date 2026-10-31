package com.vr2sbs.bilibili

import android.media.MediaCodecInfo
import android.media.MediaCodecList

interface BilibiliCodecSupport {
    fun supportsVideo(codecs: String, width: Int, height: Int): Boolean
    fun supportsAudio(codecs: String): Boolean
}

/**
 * Checks the device's decoders so the highest Bilibili quality that can actually be
 * played is selected (for example, skipping AV1 when only AVC/HEVC is advertised).
 */
class AndroidBilibiliCodecSupport : BilibiliCodecSupport {
    private val videoCapabilities: Map<String, List<MediaCodecInfo.VideoCapabilities>> by lazy {
        buildCapabilities()
    }
    private val audioMimeTypes: Set<String> by lazy {
        buildAudioMimeTypes()
    }

    override fun supportsVideo(codecs: String, width: Int, height: Int): Boolean {
        val mime = videoMimeType(codecs) ?: return false
        val capabilities = videoCapabilities[mime] ?: return false
        if (width <= 0 || height <= 0) {
            return true
        }
        return capabilities.any { capability ->
            runCatching { capability.isSizeSupported(width, height) }.getOrDefault(false)
        }
    }

    override fun supportsAudio(codecs: String): Boolean {
        val mime = audioMimeType(codecs) ?: return false
        return mime in audioMimeTypes
    }

    private fun buildCapabilities(): Map<String, List<MediaCodecInfo.VideoCapabilities>> {
        val result = mutableMapOf<String, MutableList<MediaCodecInfo.VideoCapabilities>>()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        }.getOrDefault(emptyArray()).forEach codecLoop@{ info ->
            if (info.isEncoder) {
                return@codecLoop
            }
            info.supportedTypes.forEach typeLoop@{ type ->
                if (!type.startsWith("video/", ignoreCase = true)) {
                    return@typeLoop
                }
                val capabilities = runCatching {
                    info.getCapabilitiesForType(type).videoCapabilities
                }.getOrNull() ?: return@typeLoop
                result.getOrPut(type.lowercase()) { mutableListOf() }.add(capabilities)
            }
        }
        return result
    }

    private fun buildAudioMimeTypes(): Set<String> {
        val result = mutableSetOf<String>()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        }.getOrDefault(emptyArray()).forEach { info ->
            if (!info.isEncoder) {
                info.supportedTypes
                    .filter { it.startsWith("audio/", ignoreCase = true) }
                    .mapTo(result) { it.lowercase() }
            }
        }
        return result
    }

    private fun videoMimeType(codecs: String): String? = when {
        codecs.startsWith("avc", ignoreCase = true) -> "video/avc"
        codecs.startsWith("hev", ignoreCase = true) ||
            codecs.startsWith("hvc", ignoreCase = true) -> "video/hevc"
        codecs.startsWith("av01", ignoreCase = true) -> "video/av01"
        codecs.startsWith("vp09", ignoreCase = true) -> "video/x-vnd.on2.vp9"
        codecs.startsWith("mp4v", ignoreCase = true) -> "video/mp4v-es"
        else -> null
    }

    private fun audioMimeType(codecs: String): String? = when {
        codecs.startsWith("mp4a", ignoreCase = true) -> "audio/mp4a-latm"
        codecs.startsWith("flac", ignoreCase = true) -> "audio/flac"
        codecs.startsWith("ec-3", ignoreCase = true) -> "audio/eac3"
        codecs.startsWith("ac-4", ignoreCase = true) -> "audio/ac4"
        codecs.startsWith("opus", ignoreCase = true) -> "audio/opus"
        else -> null
    }
}
