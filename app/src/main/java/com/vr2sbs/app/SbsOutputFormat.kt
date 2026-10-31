package com.vr2sbs.app

import kotlin.math.abs

/**
 * Output layout of the SBS frame shown on the glasses.
 *
 * HALF packs both eyes into a 1920x1080 signal (960x1080 per eye), which is the
 * fallback for USB links that cannot carry a 3840x1080 frame. FULL uses the
 * native 3840x1080 layout (1920x1080 per eye).
 */
enum class SbsOutputFormat(val outputWidthPixels: Int) {
    HALF(1920),
    FULL(3840);

    /**
     * Width the renderer must use regardless of the display mode reported by the glasses,
     * or null to follow the display surface size.
     */
    fun forcedRenderWidthPixels(): Int? = when (this) {
        HALF -> outputWidthPixels
        FULL -> null
    }

    companion object {
        const val OUTPUT_HEIGHT_PIXELS = 1080
        const val PREFERENCE_KEY_SBS_OUTPUT_FORMAT = "sbs_output_format"

        fun fromPreference(value: String?): SbsOutputFormat {
            if (value.isNullOrBlank()) {
                return FULL
            }
            return runCatching { valueOf(value) }.getOrDefault(FULL)
        }

        fun toPreference(format: SbsOutputFormat): String {
            return format.name
        }
    }
}

internal data class DisplayModeCandidate(
    val modeId: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateHz: Float
)

/**
 * Picks the display mode that matches the requested SBS output size, preferring
 * the refresh rate currently in use so switching formats does not also change
 * the perceived motion cadence.
 */
internal fun selectSbsDisplayModeId(
    format: SbsOutputFormat,
    currentRefreshRateHz: Float,
    modes: List<DisplayModeCandidate>
): Int? {
    val candidates = modes.filter {
        it.widthPixels == format.outputWidthPixels &&
            it.heightPixels == SbsOutputFormat.OUTPUT_HEIGHT_PIXELS
    }
    if (candidates.isEmpty()) {
        return null
    }
    val referenceRefreshRate = currentRefreshRateHz.takeIf { it.isFinite() && it > 0f }
    val comparator = compareBy<DisplayModeCandidate> {
        if (referenceRefreshRate == null) 0f else abs(it.refreshRateHz - referenceRefreshRate)
    }
        .thenByDescending { it.refreshRateHz }
        .thenBy { it.modeId }
    return candidates.minWithOrNull(comparator)?.modeId
}
