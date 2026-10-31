package com.vr2sbs.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbEntryFormattingTest {
    @Test
    fun formatsDurationsForTheDetailsLine() {
        assertEquals("0:59", formatSmbDuration(59_000L))
        assertEquals("1:00", formatSmbDuration(60_000L))
        assertEquals("12:34", formatSmbDuration(754_000L))
        assertEquals("1:02:03", formatSmbDuration(3_723_000L))
    }

    @Test
    fun omitsUnknownDurations() {
        assertNull(formatSmbDuration(0L))
        assertNull(formatSmbDuration(-1L))
    }
}
