package com.vr2sbs.smb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SmbBookmarkStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadAll(): List<SmbBookmark> {
        val raw = preferences.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (index in 0 until array.length()) {
                val bookmark = runCatching { decodeBookmark(array.getJSONObject(index)) }.getOrNull()
                if (bookmark != null) add(bookmark)
            }
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun find(uri: String): SmbBookmark? {
        val key = smbBookmarkKey(uri)
        return loadAll().firstOrNull { smbBookmarkKey(it.uri) == key }
    }

    @Synchronized
    fun isBookmarked(uri: String): Boolean = find(uri) != null

    @Synchronized
    fun save(bookmark: SmbBookmark) {
        val normalized = bookmark.copy(uri = normalizeSmbBookmarkUri(bookmark.uri))
        val key = smbBookmarkKey(normalized.uri)
        write(loadAll().filterNot { smbBookmarkKey(it.uri) == key } + normalized)
    }

    @Synchronized
    fun remove(uri: String) {
        val key = smbBookmarkKey(uri)
        write(loadAll().filterNot { smbBookmarkKey(it.uri) == key })
    }

    @Synchronized
    fun removeForProfile(profileId: String) {
        write(loadAll().filterNot { it.profileId == profileId })
    }

    private fun write(bookmarks: List<SmbBookmark>) {
        val array = JSONArray()
        bookmarks.sortedByDescending { it.createdAt }.forEach { array.put(encodeBookmark(it)) }
        check(preferences.edit().putString(KEY_BOOKMARKS, array.toString()).commit()) {
            "Unable to save SMB bookmarks"
        }
    }

    private fun encodeBookmark(bookmark: SmbBookmark): JSONObject = JSONObject()
        .put("uri", bookmark.uri)
        .put("profile_id", bookmark.profileId)
        .put("created_at", bookmark.createdAt)

    private fun decodeBookmark(json: JSONObject): SmbBookmark = SmbBookmark(
        uri = json.getString("uri"),
        profileId = json.getString("profile_id"),
        createdAt = json.optLong("created_at")
    )

    companion object {
        const val PREFERENCES_NAME = "smb_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks_v1"
    }
}
