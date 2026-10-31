package com.vr2sbs.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrImageLoaderTest {
    @Test
    fun detectsWidePhotoWithMatchingHalvesAsSideBySide() {
        assertTrue(VrImageLoader.isSideBySideStereo(2000, 1000, 6.0))
        assertTrue(VrImageLoader.isSideBySideStereo(1200, 720, 6.0))
    }

    @Test
    fun rejectsWidePhotoWhenHalvesDiffer() {
        assertFalse(VrImageLoader.isSideBySideStereo(2000, 1000, 24.0))
    }

    @Test
    fun rejectsNormalAspectPhotosEvenWhenHalvesMatch() {
        assertFalse(VrImageLoader.isSideBySideStereo(4000, 3000, 2.0))
        assertFalse(VrImageLoader.isSideBySideStereo(1080, 1920, 2.0))
    }

    @Test
    fun rejectsInvalidDimensions() {
        assertFalse(VrImageLoader.isSideBySideStereo(0, 1000, 0.0))
        assertFalse(VrImageLoader.isSideBySideStereo(2000, 0, 0.0))
        assertFalse(VrImageLoader.isSideBySideStereo(-1, -1, 0.0))
    }

    @Test
    fun thresholdIsInclusive() {
        assertTrue(VrImageLoader.isSideBySideStereo(2000, 1000, 12.0))
        assertFalse(VrImageLoader.isSideBySideStereo(2000, 1000, 12.01))
    }
}
