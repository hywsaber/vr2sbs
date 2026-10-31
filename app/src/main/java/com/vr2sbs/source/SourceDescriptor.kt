package com.vr2sbs.source

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SourceDescriptor(
    val original: String,
    val normalized: String,
    val type: SourceType,
    val displayName: String? = null,
    val profileId: String? = null,
    val mediaKind: SourceMediaKind = SourceMediaKind.VIDEO,
    val playlist: List<SourcePlaylistItem> = emptyList(),
    /** Actual media URL when it differs from the normalized page URL (for example a Bilibili DASH stream). */
    val streamUri: String? = null,
    /** Separate audio URL for sources served as split DASH video/audio tracks. */
    val audioStreamUri: String? = null,
    /** HTTP headers required by the stream host, such as Referer and Cookie. */
    val httpHeaders: Map<String, String> = emptyMap()
) : Parcelable {
    val isImage: Boolean get() = mediaKind == SourceMediaKind.IMAGE

    val playbackUri: String get() = streamUri ?: normalized

    /**
     * Identifies the media that is currently loaded. Stream URLs can change for the same
     * page, so playback decisions use this instead of [normalized].
     */
    val playbackKey: String
        get() = buildString {
            append(playbackUri)
            audioStreamUri?.takeIf { it.isNotBlank() }?.let { append('|').append(it) }
            if (httpHeaders.isNotEmpty()) append('|').append(httpHeaders.hashCode())
        }

    fun toUri(): Uri = Uri.parse(playbackUri)
}

/** One photo in the current folder, used for previous/next navigation. */
@Parcelize
data class SourcePlaylistItem(
    val uri: String,
    val displayName: String
) : Parcelable

enum class SourceType {
    LOCAL_URI,
    HTTP_URL,
    SMB_URI
}

enum class SourceMediaKind {
    VIDEO,
    IMAGE
}
