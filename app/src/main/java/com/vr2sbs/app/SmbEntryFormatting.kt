package com.vr2sbs.app

import android.content.Context
import android.text.format.Formatter
import com.vr2sbs.R
import com.vr2sbs.smb.MediaEntry
import com.vr2sbs.smb.SmbVideoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the smaller details block under an SMB file name. Resolution and
 * duration are omitted until metadata has been read; size and date are known
 * as soon as the folder listing arrives.
 */
internal fun formatSmbEntryDetails(
    context: Context,
    entry: MediaEntry,
    metadata: SmbVideoMetadata?
): String {
    val mediaParts = buildList {
        metadata?.let { info ->
            if (info.width > 0 && info.height > 0) {
                add(context.getString(R.string.smb_entry_resolution, info.width, info.height))
            }
            formatSmbDuration(info.durationMs)?.let(::add)
        }
    }
    val fileParts = buildList {
        if (entry.size > 0L) {
            add(Formatter.formatShortFileSize(context, entry.size))
        }
        if (entry.modifiedTime > 0L) {
            add(formatSmbModifiedDate(entry.modifiedTime))
        }
    }
    return listOf(
        mediaParts.joinToString(SMB_ENTRY_DETAILS_SEPARATOR),
        fileParts.joinToString(SMB_ENTRY_DETAILS_SEPARATOR)
    )
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

internal fun formatSmbDuration(durationMs: Long): String? {
    if (durationMs <= 0L) return null
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun formatSmbModifiedDate(modifiedTimeMs: Long): String =
    SimpleDateFormat(SMB_ENTRY_DATE_PATTERN, Locale.getDefault()).format(Date(modifiedTimeMs))

private const val SMB_ENTRY_DETAILS_SEPARATOR = " · "
private const val SMB_ENTRY_DATE_PATTERN = "yyyy-MM-dd"
