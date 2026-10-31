package com.vr2sbs.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbBrowserPolicyTest {
    @Test
    fun detectsVideoFromKnownExtension() {
        assertTrue(isSmbVideo(entry("sample.M2TS")))
    }

    @Test
    fun rejectsNonVideoFilesAndDirectories() {
        assertFalse(isSmbVideo(entry("notes.txt")))
        assertFalse(isSmbVideo(entry("movies", directory = true)))
    }

    @Test
    fun detectsPhotosFromKnownExtensions() {
        assertTrue(isSmbImage(entry("cover.JPG")))
        assertTrue(isSmbImage(entry("panorama.heic")))
        assertFalse(isSmbImage(entry("clip.mp4")))
        assertFalse(isSmbImage(entry("notes.txt")))
        assertFalse(isSmbImage(entry("photos", directory = true)))
        assertTrue(isSmbMedia(entry("cover.webp")))
        assertTrue(isSmbMedia(entry("clip.webm")))
        assertFalse(isSmbMedia(entry("notes.txt")))
    }

    @Test
    fun keepsPhotosInTheListingAndSortsThemWithVideos() {
        val entries = listOf(
            entry("new-photo.jpg", modified = 400L),
            entry("old-photo.png", modified = 100L),
            entry("clip.mp4", modified = 300L),
            entry("ignored.txt")
        )

        val sorted = sortSmbEntries(entries, SmbSortOrder.NEWEST_FIRST).map { it.name }

        assertEquals(listOf("new-photo.jpg", "clip.mp4", "old-photo.png"), sorted)
    }

    @Test
    fun sortsPhotosByResolutionAlongsideVideos() {
        val entries = listOf(
            entry("small.jpg"),
            entry("large.png"),
            entry("video.mp4")
        )
        val metadata = mapOf(
            "small.jpg" to SmbVideoMetadata(1920, 960, 0L),
            "large.png" to SmbVideoMetadata(7680, 3840, 0L),
            "video.mp4" to SmbVideoMetadata(3840, 1920, 60_000L)
        )

        val sorted = sortSmbEntries(entries, SmbSortOrder.RESOLUTION_DESC) {
            metadata[it.name]
        }.map { it.name }

        assertEquals(listOf("large.png", "video.mp4", "small.jpg"), sorted)
    }

    @Test
    fun sortsDirectoriesBeforeVideosAndFiltersOtherFiles() {
        val entries = listOf(
            entry("old-video.mp4", modified = 100L),
            entry("new-folder", directory = true, modified = 400L),
            entry("new-video.mkv", modified = 300L),
            entry("old-folder", directory = true, modified = 200L),
            entry("ignored.txt")
        )

        val sorted = sortSmbEntries(entries, SmbSortOrder.NEWEST_FIRST).map { it.name }

        assertEquals(listOf("new-folder", "old-folder", "new-video.mkv", "old-video.mp4"), sorted)
    }

    @Test
    fun supportsOldestFirstAndUnknownTimesLast() {
        val entries = listOf(
            entry("unknown.mp4"),
            entry("new.mkv", modified = 300L),
            entry("old.mp4", modified = 100L)
        )

        val sorted = sortSmbEntries(entries, SmbSortOrder.OLDEST_FIRST).map { it.name }

        assertEquals(listOf("old.mp4", "new.mkv", "unknown.mp4"), sorted)
    }

    @Test
    fun sortsByResolutionWithUnknownMetadataLast() {
        val entries = listOf(
            entry("small.mp4", modified = 100L),
            entry("unknown.mkv", modified = 200L),
            entry("large.mp4", modified = 300L),
            entry("middle.mp4", modified = 400L)
        )
        val metadata = mapOf(
            "small.mp4" to SmbVideoMetadata(1920, 1080, 60_000L),
            "large.mp4" to SmbVideoMetadata(7680, 3840, 60_000L),
            "middle.mp4" to SmbVideoMetadata(3840, 1920, 60_000L)
        )

        val sorted = sortSmbEntries(entries, SmbSortOrder.RESOLUTION_DESC) {
            metadata[it.name]
        }.map { it.name }

        assertEquals(
            listOf("large.mp4", "middle.mp4", "small.mp4", "unknown.mkv"),
            sorted
        )
    }

    @Test
    fun sortsByDurationAndSizeDescending() {
        val entries = listOf(
            entry("short.mp4", modified = 100L),
            entry("long.mp4", modified = 200L),
            entry("unknown.mp4", modified = 300L)
        )
        val metadata = mapOf(
            "short.mp4" to SmbVideoMetadata(1920, 1080, 30_000L),
            "long.mp4" to SmbVideoMetadata(1920, 1080, 3_600_000L)
        )

        val byDuration = sortSmbEntries(entries, SmbSortOrder.DURATION_DESC) {
            metadata[it.name]
        }.map { it.name }
        assertEquals(listOf("long.mp4", "short.mp4", "unknown.mp4"), byDuration)

        val sized = listOf(
            entry("small.mp4", size = 10L),
            entry("large.mp4", size = 1_000L),
            entry("zero.mp4", size = 0L)
        )
        val bySize = sortSmbEntries(sized, SmbSortOrder.SIZE_DESC).map { it.name }
        assertEquals(listOf("large.mp4", "small.mp4", "zero.mp4"), bySize)
    }

    @Test
    fun sortOrderFallsBackToNewestFirstForUnknownPreference() {
        assertEquals(SmbSortOrder.NEWEST_FIRST, SmbSortOrder.fromStoredValue(null))
        assertEquals(SmbSortOrder.NEWEST_FIRST, SmbSortOrder.fromStoredValue("bogus"))
        assertEquals(
            SmbSortOrder.RESOLUTION_DESC,
            SmbSortOrder.fromStoredValue("RESOLUTION_DESC")
        )
    }

    @Test
    fun profileIdentityIsStableButAccountSpecific() {
        val first = SmbProfile.create("nas.local", "media", "", "neo", "one")
        val sameIdentity = SmbProfile.create("NAS.LOCAL", "MEDIA", "", "NEO", "two")
        val anotherAccount = SmbProfile.create("nas.local", "media", "", "guest", "one")

        assertEquals(first.id, sameIdentity.id)
        assertNotEquals(first.id, anotherAccount.id)
    }

    private fun entry(
        name: String,
        directory: Boolean = false,
        modified: Long = 0L,
        size: Long = 100L
    ) = MediaEntry(
        uri = "smb://nas/media/$name${if (directory) "/" else ""}",
        name = name,
        directory = directory,
        size = if (directory) 0L else size,
        modifiedTime = modified
    )
}
