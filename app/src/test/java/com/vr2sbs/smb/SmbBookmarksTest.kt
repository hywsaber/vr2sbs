package com.vr2sbs.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbBookmarksTest {
    @Test
    fun normalizesFolderUrisWithTrailingSlash() {
        assertEquals("smb://nas/media/movies/", normalizeSmbBookmarkUri(" smb://nas/media/movies "))
        assertEquals("smb://nas/media/", normalizeSmbBookmarkUri("smb://nas/media/"))
    }

    @Test
    fun bookmarkKeysIgnoreCaseAndTrailingSlash() {
        assertEquals(
            smbBookmarkKey("smb://NAS/Media/Movies/"),
            smbBookmarkKey("smb://nas/media/movies")
        )
    }

    @Test
    fun displayPathOmitsSchemeAndTrailingSlash() {
        assertEquals(
            "nas/media/movies",
            SmbBookmark("smb://nas/media/movies/", "profile", 1L).displayPath
        )
        assertEquals("nas", SmbBookmark("smb://nas/", "profile", 1L).displayPath)
    }

    @Test
    fun ancestorsWalkFromRootToImmediateParent() {
        assertEquals(
            listOf(
                "smb://nas/media/",
                "smb://nas/media/movies/",
                "smb://nas/media/movies/2024/"
            ),
            smbFolderAncestors("smb://nas/media/", "smb://nas/media/movies/2024/action/")
        )
    }

    @Test
    fun ancestorsOfShareRootContainOnlyDeviceRoot() {
        assertEquals(
            listOf("smb://nas/"),
            smbFolderAncestors("smb://nas/", "smb://nas/media/")
        )
    }

    @Test
    fun ancestorsMatchRootIgnoringCase() {
        assertEquals(
            listOf("smb://NAS/Media/", "smb://NAS/Media/movies/"),
            smbFolderAncestors("smb://NAS/Media/", "smb://nas/media/movies/2024/")
        )
    }

    @Test
    fun ancestorsEmptyForRootOrFolderOutsideShare() {
        assertTrue(smbFolderAncestors("smb://nas/media/", "smb://nas/media/").isEmpty())
        assertTrue(
            smbFolderAncestors("smb://nas/media/", "smb://nas/other/").isEmpty()
        )
    }
}
