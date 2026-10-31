package com.vr2sbs.smb

import java.util.Locale

/**
 * A saved SMB folder that can be reopened from the connection page.
 *
 * The profile id points at an entry in [SmbProfileStore], which holds the
 * encrypted credentials needed to reconnect without asking again.
 */
data class SmbBookmark(
    val uri: String,
    val profileId: String,
    val createdAt: Long
) {
    val displayPath: String
        get() {
            val normalized = normalizeSmbBookmarkUri(uri)
            return normalized.removePrefix("smb://").trimEnd('/').ifBlank { normalized }
        }
}

internal fun normalizeSmbBookmarkUri(uri: String): String {
    val trimmed = uri.trim()
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}

internal fun smbBookmarkKey(uri: String): String =
    normalizeSmbBookmarkUri(uri).lowercase(Locale.ROOT)

/**
 * Returns the folders between [rootUri] and [folderUri], starting with the
 * root and ending with the folder's immediate parent. Empty when the target
 * is the root itself or is outside the share.
 */
internal fun smbFolderAncestors(rootUri: String, folderUri: String): List<String> {
    val root = normalizeSmbBookmarkUri(rootUri)
    val folder = normalizeSmbBookmarkUri(folderUri)
    if (!folder.startsWith(root, ignoreCase = true)) return emptyList()
    val relative = folder.substring(root.length).trim('/')
    if (relative.isEmpty()) return emptyList()
    val segments = relative.split('/')
    val ancestors = mutableListOf(root)
    var current = root
    for (index in 0 until segments.lastIndex) {
        current += segments[index] + "/"
        ancestors.add(current)
    }
    return ancestors
}
