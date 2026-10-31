package com.vr2sbs.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SbsOutputFormatTest {
    @Test
    fun preferenceDefaultsToFullSbs() {
        assertEquals(SbsOutputFormat.FULL, SbsOutputFormat.fromPreference(null))
        assertEquals(SbsOutputFormat.FULL, SbsOutputFormat.fromPreference(""))
        assertEquals(SbsOutputFormat.FULL, SbsOutputFormat.fromPreference("unknown"))
    }

    @Test
    fun preferenceParsesKnownFormats() {
        assertEquals(SbsOutputFormat.HALF, SbsOutputFormat.fromPreference("HALF"))
        assertEquals(SbsOutputFormat.FULL, SbsOutputFormat.fromPreference("FULL"))
        assertEquals("HALF", SbsOutputFormat.toPreference(SbsOutputFormat.HALF))
        assertEquals("FULL", SbsOutputFormat.toPreference(SbsOutputFormat.FULL))
    }

    @Test
    fun outputSizesMatchSbsProtocols() {
        assertEquals(1920, SbsOutputFormat.HALF.outputWidthPixels)
        assertEquals(3840, SbsOutputFormat.FULL.outputWidthPixels)
        assertEquals(1080, SbsOutputFormat.OUTPUT_HEIGHT_PIXELS)
    }

    @Test
    fun halfOutputForcesRenderSurfaceIndependentOfDisplayModes() {
        assertEquals(1920, SbsOutputFormat.HALF.forcedRenderWidthPixels())
        assertNull(SbsOutputFormat.FULL.forcedRenderWidthPixels())
    }

    @Test
    fun selectModeMatchesRequestedResolution() {
        val modes = listOf(
            mode(modeId = 1, width = 3840, height = 1080, refresh = 60f),
            mode(modeId = 2, width = 1920, height = 1080, refresh = 60f),
            mode(modeId = 3, width = 1920, height = 720, refresh = 60f)
        )

        assertEquals(2, selectSbsDisplayModeId(SbsOutputFormat.HALF, 60f, modes))
        assertEquals(1, selectSbsDisplayModeId(SbsOutputFormat.FULL, 60f, modes))
    }

    @Test
    fun selectModePrefersRefreshRateCloseToCurrent() {
        val modes = listOf(
            mode(modeId = 1, width = 1920, height = 1080, refresh = 60f),
            mode(modeId = 2, width = 1920, height = 1080, refresh = 120f)
        )

        assertEquals(2, selectSbsDisplayModeId(SbsOutputFormat.HALF, 90f, modes))
        assertEquals(1, selectSbsDisplayModeId(SbsOutputFormat.HALF, 30f, modes))
    }

    @Test
    fun selectModeReturnsNullWhenResolutionIsUnsupported() {
        val modes = listOf(mode(modeId = 1, width = 1920, height = 1080, refresh = 60f))

        assertNull(selectSbsDisplayModeId(SbsOutputFormat.FULL, 60f, modes))
    }

    private fun mode(modeId: Int, width: Int, height: Int, refresh: Float): DisplayModeCandidate {
        return DisplayModeCandidate(
            modeId = modeId,
            widthPixels = width,
            heightPixels = height,
            refreshRateHz = refresh
        )
    }
}
