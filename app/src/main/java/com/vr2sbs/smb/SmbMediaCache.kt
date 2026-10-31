package com.vr2sbs.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Persistent cache for SMB video metadata and thumbnails.
 *
 * Entries are keyed by profile and canonical file URI and are only considered
 * valid while the file size and modified time match the latest directory
 * listing, so a changed file is read again instead of showing stale details.
 * Thumbnails are stored as JPEG files in app-private storage. Once the cache
 * grows past [capacityBytes], the least recently used entries are dropped and
 * their files deleted.
 */
class SmbMediaCache(
    context: Context,
    val capacityBytes: Long = DEFAULT_CAPACITY_BYTES
) : Closeable {
    private val rootDir = File(context.filesDir, DIRECTORY_NAME)
    private val thumbnailsDir = File(rootDir, THUMBNAIL_DIRECTORY_NAME)
    private val indexFile = File(rootDir, INDEX_FILE_NAME)
    private val lock = Any()
    private val ioExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, CACHE_THREAD_NAME).apply { isDaemon = true }
        }

    private val entries = HashMap<String, CacheEntry>()
    private var totalThumbnailBytes = 0L
    private var dirty = false
    private var flushScheduled = false
    private var generation = 0L
    private var closed = false

    init {
        rootDir.mkdirs()
        thumbnailsDir.mkdirs()
        loadIndex()
        deleteOrphanedThumbnails()
    }

    /** Metadata for an unchanged file, or null when it is missing or stale. */
    fun cachedMetadata(profileId: String, entry: MediaEntry): SmbVideoMetadata? {
        val cached = lookup(profileId, entry) ?: return null
        if (cached.width <= 0 && cached.height <= 0 && cached.durationMs <= 0L) return null
        return SmbVideoMetadata(
            width = cached.width,
            height = cached.height,
            durationMs = cached.durationMs
        )
    }

    /** Decodes a cached thumbnail on the calling thread. */
    fun cachedThumbnail(profileId: String, entry: MediaEntry): Bitmap? {
        val thumbnailFile = synchronized(lock) {
            val cached = entries[cacheKey(profileId, entry.uri)]
            when {
                cached == null -> null
                !cached.matches(entry) -> null
                else -> cached.thumbnailName?.let { File(thumbnailsDir, it) }
            }
        } ?: return null
        if (!thumbnailFile.isFile) {
            dropThumbnail(profileId, entry.uri)
            return null
        }
        return runCatching { BitmapFactory.decodeFile(thumbnailFile.absolutePath) }
            .getOrNull()
            ?: run {
                dropThumbnail(profileId, entry.uri)
                null
            }
    }

    fun putMetadata(profileId: String, entry: MediaEntry, metadata: SmbVideoMetadata?) {
        metadata ?: return
        var staleThumbnail: String? = null
        synchronized(lock) {
            if (closed) return
            val key = cacheKey(profileId, entry.uri)
            val existing = entries[key]
            if (existing != null && !existing.matches(entry)) {
                entries.remove(key)
                totalThumbnailBytes -= existing.thumbnailBytes
                staleThumbnail = existing.thumbnailName
            }
            val target = entries.getOrPut(key) {
                CacheEntry(
                    profileId = profileId,
                    uri = entry.uri,
                    size = entry.size,
                    modifiedTime = entry.modifiedTime
                )
            }
            target.width = metadata.width
            target.height = metadata.height
            target.durationMs = metadata.durationMs
            target.lastAccessMs = System.currentTimeMillis()
            dirty = true
        }
        staleThumbnail?.let { deleteThumbnailAsync(it) }
        scheduleFlush()
    }

    /**
     * Compresses and stores a thumbnail on a background thread. A cached copy
     * of a file that has since changed size or date is discarded.
     */
    fun putThumbnail(profileId: String, entry: MediaEntry, bitmap: Bitmap) {
        val jpeg = runCatching {
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)) {
                    output.toByteArray()
                } else {
                    null
                }
            }
        }.getOrNull() ?: return
        if (jpeg.isEmpty()) return

        val key = cacheKey(profileId, entry.uri)
        val fileName = thumbnailFileName(key)
        val scheduledGeneration = synchronized(lock) {
            if (closed) return
            generation
        }
        ioExecutor.execute {
            if (!isCurrentGeneration(scheduledGeneration)) return@execute
            val thumbnailFile = File(thumbnailsDir, fileName)
            val tempFile = File(thumbnailsDir, "$fileName$TEMP_SUFFIX")
            val written = runCatching {
                tempFile.writeBytes(jpeg)
                if (thumbnailFile.exists()) thumbnailFile.delete()
                if (!tempFile.renameTo(thumbnailFile)) error("Could not store thumbnail")
                true
            }.getOrDefault(false)
            if (!written) {
                tempFile.delete()
                return@execute
            }

            val deletions = mutableListOf<String>()
            var discardWrittenFile = false
            synchronized(lock) {
                if (closed || !isCurrentGeneration(scheduledGeneration)) {
                    discardWrittenFile = true
                } else {
                    val cached = entries[key]
                    if (cached != null && !cached.matches(entry)) {
                        // The file changed since it was listed; keep the new
                        // thumbnail but drop the old entry's details.
                        totalThumbnailBytes -= cached.thumbnailBytes
                        cached.thumbnailName
                            ?.takeIf { it != fileName }
                            ?.let(deletions::add)
                        entries.remove(key)
                    }
                    // Note: the thumbnail file name is derived from the key
                    // alone, so an old file with the same name was already
                    // replaced by the rename above and must not be deleted.
                    val target = entries.getOrPut(key) {
                        CacheEntry(
                            profileId = profileId,
                            uri = entry.uri,
                            size = entry.size,
                            modifiedTime = entry.modifiedTime
                        )
                    }
                    target.thumbnailName
                        ?.takeIf { it != fileName }
                        ?.let(deletions::add)
                    totalThumbnailBytes -= target.thumbnailBytes
                    target.thumbnailName = fileName
                    target.thumbnailBytes = thumbnailFile.length()
                    target.lastAccessMs = System.currentTimeMillis()
                    totalThumbnailBytes += target.thumbnailBytes
                    dirty = true
                    deletions += evictLocked()
                }
            }
            if (discardWrittenFile) {
                thumbnailFile.delete()
                return@execute
            }
            deletions.forEach { File(thumbnailsDir, it).delete() }
            scheduleFlush()
        }
    }

    /** Total bytes currently used by cached thumbnails. */
    fun totalBytes(): Long = synchronized(lock) { totalThumbnailBytes }

    /** Number of cached files, exposed for diagnostics and tests. */
    fun entryCount(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            if (closed) return
            generation += 1
            entries.clear()
            totalThumbnailBytes = 0L
            dirty = false
            flushScheduled = false
        }
        ioExecutor.execute {
            thumbnailsDir.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { indexFile.delete() }
            runCatching { File(rootDir, "$INDEX_FILE_NAME$TEMP_SUFFIX").delete() }
        }
    }

    /** Writes pending changes to disk as soon as the cache thread is free. */
    fun flush() {
        synchronized(lock) {
            if (closed) return
            dirty = true
            flushScheduled = true
        }
        ioExecutor.execute {
            synchronized(lock) { flushScheduled = false }
            writeIndex()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            flushScheduled = false
        }
        runCatching { writeIndexNow() }
        ioExecutor.shutdown()
    }

    private fun lookup(profileId: String, entry: MediaEntry): CacheEntry? {
        var staleThumbnail: String? = null
        val cached = synchronized(lock) {
            val key = cacheKey(profileId, entry.uri)
            val found = entries[key] ?: return null
            if (!found.matches(entry)) {
                entries.remove(key)
                totalThumbnailBytes -= found.thumbnailBytes
                staleThumbnail = found.thumbnailName
                dirty = true
                return null
            }
            found.lastAccessMs = System.currentTimeMillis()
            dirty = true
            found
        }
        staleThumbnail?.let { deleteThumbnailAsync(it) }
        return cached
    }

    private fun dropThumbnail(profileId: String, uri: String) {
        var deletedName: String? = null
        synchronized(lock) {
            val key = cacheKey(profileId, uri)
            val cached = entries[key] ?: return
            deletedName = cached.thumbnailName
            totalThumbnailBytes -= cached.thumbnailBytes
            cached.thumbnailName = null
            cached.thumbnailBytes = 0L
            dirty = true
        }
        deletedName?.let { deleteThumbnailAsync(it) }
    }

    /** Returns thumbnail file names that were removed from the index. */
    private fun evictLocked(): List<String> {
        val removed = mutableListOf<String>()
        while (totalThumbnailBytes > capacityBytes && entries.size > 1) {
            val oldest = entries.values
                .filter { it.thumbnailName != null }
                .minByOrNull { it.lastAccessMs }
                ?: break
            totalThumbnailBytes -= oldest.thumbnailBytes
            oldest.thumbnailName?.let(removed::add)
            entries.remove(cacheKey(oldest.profileId, oldest.uri))
        }
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.values.minByOrNull { it.lastAccessMs } ?: break
            totalThumbnailBytes -= oldest.thumbnailBytes
            oldest.thumbnailName?.let(removed::add)
            entries.remove(cacheKey(oldest.profileId, oldest.uri))
        }
        return removed
    }

    private fun loadIndex() {
        if (!indexFile.isFile) return
        val root = runCatching { JSONObject(indexFile.readText()) }
            .getOrElse {
                indexFile.delete()
                return
            }
        if (root.optInt(FIELD_VERSION, 0) != INDEX_VERSION) {
            indexFile.delete()
            return
        }
        val array = root.optJSONArray(FIELD_ENTRIES) ?: return
        val skippedThumbnails = mutableListOf<String>()
        var total = 0L
        synchronized(lock) {
            for (position in 0 until array.length()) {
                if (entries.size >= MAX_ENTRIES) break
                val json = array.optJSONObject(position) ?: continue
                val profileId = json.optString(FIELD_PROFILE_ID)
                val uri = json.optString(FIELD_URI)
                if (profileId.isEmpty() || uri.isEmpty()) continue
                val thumbnailName = json.optString(FIELD_THUMBNAIL)
                    .takeIf { it.isNotEmpty() }
                val thumbnailFile = thumbnailName?.let { File(thumbnailsDir, it) }
                val thumbnailExists = thumbnailFile?.isFile == true
                if (thumbnailName != null && !thumbnailExists) {
                    skippedThumbnails += thumbnailName
                }
                val thumbnailBytes = if (thumbnailExists) thumbnailFile!!.length() else 0L
                val cached = CacheEntry(
                    profileId = profileId,
                    uri = uri,
                    size = json.optLong(FIELD_SIZE),
                    modifiedTime = json.optLong(FIELD_MODIFIED_TIME),
                    width = json.optInt(FIELD_WIDTH),
                    height = json.optInt(FIELD_HEIGHT),
                    durationMs = json.optLong(FIELD_DURATION),
                    thumbnailName = thumbnailName.takeIf { thumbnailExists },
                    thumbnailBytes = thumbnailBytes,
                    lastAccessMs = json.optLong(FIELD_LAST_ACCESS)
                )
                entries[cacheKey(profileId, uri)] = cached
                total += thumbnailBytes
            }
            totalThumbnailBytes = total
            evictLocked().forEach { skippedThumbnails += it }
        }
        skippedThumbnails.forEach { File(thumbnailsDir, it).delete() }
    }

    private fun writeIndex() {
        val json = synchronized(lock) {
            if (closed || !dirty) return
            dirty = false
            buildIndexJsonLocked()
        }
        runCatching { writeIndexFile(json) }
            .onFailure { synchronized(lock) { dirty = true } }
    }

    private fun writeIndexNow() {
        val json = synchronized(lock) { buildIndexJsonLocked() }
        runCatching { writeIndexFile(json) }
    }

    private fun buildIndexJsonLocked(): String {
        val array = JSONArray()
        entries.values
            .sortedByDescending { it.lastAccessMs }
            .forEach { cached ->
                array.put(
                    JSONObject()
                        .put(FIELD_PROFILE_ID, cached.profileId)
                        .put(FIELD_URI, cached.uri)
                        .put(FIELD_SIZE, cached.size)
                        .put(FIELD_MODIFIED_TIME, cached.modifiedTime)
                        .put(FIELD_WIDTH, cached.width)
                        .put(FIELD_HEIGHT, cached.height)
                        .put(FIELD_DURATION, cached.durationMs)
                        .put(FIELD_THUMBNAIL, cached.thumbnailName ?: "")
                        .put(FIELD_LAST_ACCESS, cached.lastAccessMs)
                )
            }
        return JSONObject()
            .put(FIELD_VERSION, INDEX_VERSION)
            .put(FIELD_ENTRIES, array)
            .toString()
    }

    private fun writeIndexFile(json: String) {
        val tempFile = File(rootDir, "$INDEX_FILE_NAME$TEMP_SUFFIX")
        tempFile.writeText(json)
        if (tempFile.renameTo(indexFile)) return
        // The rename is atomic on Android; this fallback covers a filesystem
        // that refuses to replace an existing file.
        indexFile.delete()
        if (!tempFile.renameTo(indexFile)) {
            tempFile.delete()
            indexFile.writeText(json)
        }
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (closed || flushScheduled) return
            flushScheduled = true
        }
        ioExecutor.schedule(
            {
                synchronized(lock) { flushScheduled = false }
                writeIndex()
            },
            FLUSH_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun deleteThumbnailAsync(name: String) {
        ioExecutor.execute { runCatching { File(thumbnailsDir, name).delete() } }
    }

    private fun isCurrentGeneration(value: Long): Boolean =
        synchronized(lock) { !closed && generation == value }

    private fun deleteOrphanedThumbnails() {
        val knownNames = synchronized(lock) {
            entries.values.mapNotNullTo(mutableSetOf()) { it.thumbnailName }
        }
        val scheduledGeneration = synchronized(lock) { generation }
        ioExecutor.execute {
            if (!isCurrentGeneration(scheduledGeneration)) return@execute
            thumbnailsDir.listFiles()?.forEach { file ->
                if (file.name !in knownNames) runCatching { file.delete() }
            }
        }
    }

    private class CacheEntry(
        val profileId: String,
        val uri: String,
        val size: Long,
        val modifiedTime: Long,
        var width: Int = 0,
        var height: Int = 0,
        var durationMs: Long = 0L,
        var thumbnailName: String? = null,
        var thumbnailBytes: Long = 0L,
        var lastAccessMs: Long = System.currentTimeMillis()
    ) {
        fun matches(entry: MediaEntry): Boolean =
            size == entry.size && modifiedTime == entry.modifiedTime
    }

    companion object {
        const val DEFAULT_CAPACITY_BYTES: Long = 128L * 1024L * 1024L

        private const val DIRECTORY_NAME = "smb_media_cache"
        private const val THUMBNAIL_DIRECTORY_NAME = "thumbnails"
        private const val INDEX_FILE_NAME = "index.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val CACHE_THREAD_NAME = "smb-media-cache"
        private const val INDEX_VERSION = 1
        private const val MAX_ENTRIES = 20_000
        private const val THUMBNAIL_QUALITY = 82
        private const val FLUSH_DELAY_MS = 1_500L

        private const val FIELD_VERSION = "version"
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_PROFILE_ID = "profile"
        private const val FIELD_URI = "uri"
        private const val FIELD_SIZE = "size"
        private const val FIELD_MODIFIED_TIME = "modified"
        private const val FIELD_WIDTH = "width"
        private const val FIELD_HEIGHT = "height"
        private const val FIELD_DURATION = "duration"
        private const val FIELD_THUMBNAIL = "thumb"
        private const val FIELD_LAST_ACCESS = "access"

        private fun cacheKey(profileId: String, uri: String): String = "$profileId\u0000$uri"

        private fun thumbnailFileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            } + ".jpg"
        }
    }
}
