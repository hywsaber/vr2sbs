package com.vr2sbs.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatsTest {
    @Test
    fun detectsCommonPhotoExtensionsCaseInsensitively() {
        assertTrue(isImageFileName("cover.JPG"))
        assertTrue(isImageFileName("panorama.jpeg"))
        assertTrue(isImageFileName("photo.heic"))
        assertTrue(isImageFileName("photo.AVIF"))
        assertFalse(isImageFileName("movie.mp4"))
        assertFalse(isImageFileName("notes.txt"))
        assertFalse(isImageFileName(null))
    }

    @Test
    fun detectsVideoExtensionsCaseInsensitively() {
        assertTrue(isVideoFileName("movie.MKV"))
        assertTrue(isVideoFileName("clip.m2ts"))
        assertFalse(isVideoFileName("cover.jpg"))
    }

    @Test
    fun mimeTypeTakesPriorityOverTheFileName() {
        assertEquals(
            SourceMediaKind.IMAGE,
            mediaKindFor("shared-file", "image/jpeg")
        )
        assertEquals(
            SourceMediaKind.VIDEO,
            mediaKindFor("cover.jpg", "video/mp4")
        )
    }

    @Test
    fun fallsBackToTheFileNameThenToVideo() {
        assertEquals(SourceMediaKind.IMAGE, mediaKindFor("cover.jpg", null))
        assertEquals(SourceMediaKind.VIDEO, mediaKindFor("clip.mp4", null))
        assertEquals(SourceMediaKind.VIDEO, mediaKindFor("unknown.bin", null))
    }
}
