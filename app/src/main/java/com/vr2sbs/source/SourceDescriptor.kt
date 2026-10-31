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
    val playlist: List<SourcePlaylistItem> = emptyList()
) : Parcelable {
    val isImage: Boolean get() = mediaKind == SourceMediaKind.IMAGE

    fun toUri(): Uri = Uri.parse(normalized)
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
