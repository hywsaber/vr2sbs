package com.vr2sbs.smb

import com.vr2sbs.source.isImageFileName
import com.vr2sbs.source.isVideoFileName
import java.util.Locale

enum class SmbSortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    NAME_ASC,
    NAME_DESC,
    RESOLUTION_DESC,
    DURATION_DESC,
    SIZE_DESC;

    val requiresVideoMetadata: Boolean
        get() = this == RESOLUTION_DESC || this == DURATION_DESC

    companion object {
        fun fromStoredValue(value: String?): SmbSortOrder =
            entries.firstOrNull { it.name == value } ?: NEWEST_FIRST
    }
}

fun isSmbVideo(entry: MediaEntry): Boolean =
    !entry.directory && isVideoFileName(entry.name)

fun isSmbImage(entry: MediaEntry): Boolean =
    !entry.directory && isImageFileName(entry.name)

fun isSmbMedia(entry: MediaEntry): Boolean = isSmbVideo(entry) || isSmbImage(entry)

fun sortSmbEntries(
    entries: List<MediaEntry>,
    order: SmbSortOrder,
    metadata: (MediaEntry) -> SmbVideoMetadata? = { null }
): List<MediaEntry> {
    val visibleEntries = entries.filter { it.directory || isSmbMedia(it) }
    if (visibleEntries.isEmpty()) return emptyList()
    val metadataByUri = if (order.requiresVideoMetadata) {
        visibleEntries.associate { it.uri to metadata(it) }
    } else {
        emptyMap()
    }
    val comparator = compareBy<MediaEntry> { !it.directory }
        .thenComparator { left, right ->
            compareEntries(left, right, order, metadataByUri)
        }
    return visibleEntries.sortedWith(
        if (order == SmbSortOrder.NAME_DESC) {
            comparator.thenByDescending { it.name.lowercase(Locale.ROOT) }
        } else {
            comparator.thenBy { it.name.lowercase(Locale.ROOT) }
        }
    )
}

private fun compareEntries(
    left: MediaEntry,
    right: MediaEntry,
    order: SmbSortOrder,
    metadataByUri: Map<String, SmbVideoMetadata?>
): Int {
    if (left.directory && right.directory && order != SmbSortOrder.NEWEST_FIRST &&
        order != SmbSortOrder.OLDEST_FIRST
    ) {
        return 0
    }
    return when (order) {
        SmbSortOrder.NEWEST_FIRST,
        SmbSortOrder.OLDEST_FIRST -> compareModifiedTime(left, right, order)

        SmbSortOrder.NAME_ASC,
        SmbSortOrder.NAME_DESC -> 0

        SmbSortOrder.RESOLUTION_DESC -> compareDescendingKnownLast(
            metadataByUri[left.uri]?.pixelCount,
            metadataByUri[right.uri]?.pixelCount
        )

        SmbSortOrder.DURATION_DESC -> compareDescendingKnownLast(
            metadataByUri[left.uri]?.knownDurationMs,
            metadataByUri[right.uri]?.knownDurationMs
        )

        SmbSortOrder.SIZE_DESC -> compareDescendingKnownLast(
            left.size.takeIf { it > 0L },
            right.size.takeIf { it > 0L }
        )
    }
}

private fun compareModifiedTime(left: MediaEntry, right: MediaEntry, order: SmbSortOrder): Int {
    val leftUnknown = left.modifiedTime <= 0L
    val rightUnknown = right.modifiedTime <= 0L
    if (leftUnknown != rightUnknown) return if (leftUnknown) 1 else -1
    if (leftUnknown) return 0
    return when (order) {
        SmbSortOrder.NEWEST_FIRST -> right.modifiedTime.compareTo(left.modifiedTime)
        SmbSortOrder.OLDEST_FIRST -> left.modifiedTime.compareTo(right.modifiedTime)
        else -> 0
    }
}

private fun <T : Comparable<T>> compareDescendingKnownLast(left: T?, right: T?): Int {
    if (left == null) return if (right == null) 0 else 1
    if (right == null) return -1
    return right.compareTo(left)
}
