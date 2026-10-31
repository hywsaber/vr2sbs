package com.vr2sbs.app

import com.vr2sbs.source.SourcePlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePlaylistTest {
    private val playlist = listOf(
        SourcePlaylistItem("smb://nas/photos/one.jpg", "one.jpg"),
        SourcePlaylistItem("smb://nas/photos/two.jpg", "two.jpg"),
        SourcePlaylistItem("smb://nas/photos/three.jpg", "three.jpg")
    )

    @Test
    fun restoresASavedIndexWhenItIsValid() {
        assertEquals(
            2,
            initialImageIndex(playlist, "smb://nas/photos/one.jpg", restoredIndex = 2)
        )
    }

    @Test
    fun findsTheOpenedPhotoCaseInsensitively() {
        assertEquals(
            1,
            initialImageIndex(playlist, "SMB://NAS/PHOTOS/TWO.JPG", restoredIndex = -1)
        )
    }

    @Test
    fun fallsBackToTheFirstPhotoWhenNothingMatches() {
        assertEquals(
            0,
            initialImageIndex(playlist, "smb://nas/photos/missing.jpg", restoredIndex = -1)
        )
        assertEquals(
            0,
            initialImageIndex(emptyList(), "smb://nas/photos/missing.jpg", restoredIndex = 4)
        )
    }
}
