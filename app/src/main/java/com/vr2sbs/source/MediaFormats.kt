package com.vr2sbs.source

import android.net.Uri
import java.util.Locale

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "mov", "webm", "ts", "m2ts", "avi"
)

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "heic", "heif", "avif"
)

fun isVideoFileName(name: String?): Boolean = fileExtension(name) in VIDEO_EXTENSIONS

fun isImageFileName(name: String?): Boolean = fileExtension(name) in IMAGE_EXTENSIONS

fun mediaKindFor(fileName: String?, mimeType: String?): SourceMediaKind {
    val normalizedMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    return when {
        normalizedMime.startsWith("image/") -> SourceMediaKind.IMAGE
        normalizedMime.startsWith("video/") -> SourceMediaKind.VIDEO
        isImageFileName(fileName) -> SourceMediaKind.IMAGE
        else -> SourceMediaKind.VIDEO
    }
}

fun mediaKindFor(uri: Uri, mimeType: String?): SourceMediaKind =
    mediaKindFor(uri.lastPathSegment, mimeType)

private fun fileExtension(name: String?): String =
    name.orEmpty()
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
