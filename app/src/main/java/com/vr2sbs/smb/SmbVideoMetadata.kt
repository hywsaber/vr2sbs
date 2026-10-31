package com.vr2sbs.smb

/**
 * Display metadata read from a remote video without downloading it.
 *
 * A zero width, height, or duration means that value could not be read.
 */
data class SmbVideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long
) {
    val pixelCount: Long?
        get() = if (width > 0 && height > 0) width.toLong() * height else null

    val knownDurationMs: Long?
        get() = durationMs.takeIf { it > 0L }
}
