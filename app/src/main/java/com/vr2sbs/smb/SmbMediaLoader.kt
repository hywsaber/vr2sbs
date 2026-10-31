package com.vr2sbs.smb

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.vr2sbs.R
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Loads thumbnails and video metadata over SMB.
 *
 * Metadata is read on demand through [probeMetadata], which the browser drives
 * with several concurrent workers; thumbnails are only requested after the
 * metadata pass finished ([thumbnailsEnabled]). Both layers consult
 * [SmbMediaCache] first, so a folder visited before shows its details and
 * thumbnails without touching the network.
 */
class SmbMediaLoader(
    private val manager: SmbClientManager,
    private val profileId: String,
    private val thumbnailSizePx: Int,
    private val mediaCache: SmbMediaCache? = null
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(THUMBNAIL_WORKERS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val thumbnailRequests = mutableMapOf<String, MutableList<ImageView>>()
    private val queuedThumbnailKeys = mutableSetOf<String>()
    private val activeSources = Collections.synchronizedSet(mutableSetOf<SmbMediaDataSource>())
    private val thumbnailCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val metadataCache = object : LruCache<String, MetadataEntry>(METADATA_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: MetadataEntry): Int = 1
    }

    /**
     * Thumbnails are held back until the current folder's metadata has been
     * read, so the details lines appear first.
     */
    @Volatile
    var thumbnailsEnabled: Boolean = false

    /**
     * Shows a thumbnail for [entry] in [target]. Registers the view so a
     * thumbnail that is already being loaded can be delivered to it as well.
     */
    fun bind(entry: MediaEntry, target: ImageView) {
        val key = entry.mediaKey()
        target.tag = key
        target.setImageResource(R.drawable.ic_smb_video)
        if (closed.get()) return
        val memoryThumbnail = synchronized(lock) { thumbnailCache.get(key) }
        if (memoryThumbnail != null) {
            target.setImageBitmap(memoryThumbnail)
            return
        }
        if (!thumbnailsEnabled) return

        val shouldQueue = synchronized(lock) {
            if (closed.get()) return
            val requests = thumbnailRequests.getOrPut(key) { mutableListOf() }
            requests.removeAll { it === target }
            requests.add(target)
            if (key in queuedThumbnailKeys) {
                false
            } else {
                queuedThumbnailKeys.add(key)
                true
            }
        }
        if (!shouldQueue) return
        workers.execute { runThumbnailJob(entry, key) }
    }

    fun cachedMetadata(entry: MediaEntry): SmbVideoMetadata? {
        val key = entry.mediaKey()
        synchronized(lock) { metadataCache.get(key) }?.let { return it.metadata }
        if (closed.get()) return null
        return mediaCache?.cachedMetadata(profileId, entry)
    }

    /** True once metadata was read (even if the file had none) or is cached. */
    fun hasMetadata(entry: MediaEntry): Boolean {
        val key = entry.mediaKey()
        synchronized(lock) {
            if (metadataCache.get(key) != null) return true
        }
        return mediaCache?.cachedMetadata(profileId, entry) != null
    }

    /**
     * Reads metadata for one file on the calling thread. Used by the parallel
     * metadata pass. Results, including failures, are cached for this loader;
     * successful metadata is also written to the persistent cache.
     */
    fun probeMetadata(entry: MediaEntry): SmbVideoMetadata? {
        val key = entry.mediaKey()
        synchronized(lock) {
            metadataCache.get(key)?.let { return it.metadata }
        }
        mediaCache?.cachedMetadata(profileId, entry)?.let { cached ->
            synchronized(lock) {
                if (!closed.get()) metadataCache.put(key, MetadataEntry(cached))
            }
            return cached
        }
        if (closed.get()) return null
        val metadata = runCatching { loadMetadata(entry) }.getOrNull()
        synchronized(lock) {
            if (!closed.get()) metadataCache.put(key, MetadataEntry(metadata))
        }
        if (metadata != null) mediaCache?.putMetadata(profileId, entry, metadata)
        return metadata
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdownNow()
        synchronized(activeSources) {
            activeSources.toList().forEach { runCatching { it.close() } }
            activeSources.clear()
        }
        synchronized(lock) {
            thumbnailRequests.clear()
            queuedThumbnailKeys.clear()
            thumbnailCache.evictAll()
            metadataCache.evictAll()
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runThumbnailJob(entry: MediaEntry, key: String) {
        var bitmap: Bitmap? = null
        try {
            if (!closed.get() && hasBoundTarget(key)) {
                bitmap = mediaCache?.cachedThumbnail(profileId, entry)
                if (bitmap == null && thumbnailsEnabled) {
                    bitmap = loadThumbnailWithRetry(entry)
                }
            }
        } catch (_: Throwable) {
            bitmap = null
        }
        val loaded = bitmap
        val targets = synchronized(lock) {
            if (closed.get()) {
                queuedThumbnailKeys.remove(key)
                thumbnailRequests.remove(key).orEmpty()
            } else if (loaded != null) {
                thumbnailCache.put(key, loaded)
                queuedThumbnailKeys.remove(key)
                thumbnailRequests.remove(key).orEmpty()
            } else {
                // Leave the views registered: the next bind of this entry
                // will start another attempt because the key is no longer
                // marked as queued.
                queuedThumbnailKeys.remove(key)
                thumbnailRequests[key].orEmpty().toList()
            }
        }
        if (loaded == null) return
        if (!closed.get()) mediaCache?.putThumbnail(profileId, entry, loaded)
        mainHandler.post {
            if (closed.get()) return@post
            targets.forEach { target ->
                if (target.tag == key) target.setImageBitmap(loaded)
            }
        }
    }

    private fun hasBoundTarget(key: String): Boolean = synchronized(lock) {
        thumbnailRequests[key].orEmpty().any { it.tag == key }
    }

    /**
     * A failed SMB read or decoder session is retried once after a short
     * pause; within a session, the first frame is used when the frame at the
     * usual preview position is unavailable.
     */
    private fun loadThumbnailWithRetry(entry: MediaEntry): Bitmap? {
        for (attempt in 0 until THUMBNAIL_ATTEMPTS) {
            if (closed.get()) return null
            val thumbnail = runCatching { loadThumbnailSession(entry) }.getOrNull()
            if (thumbnail != null) return thumbnail
            if (attempt < THUMBNAIL_ATTEMPTS - 1) {
                val interrupted = runCatching {
                    Thread.sleep(THUMBNAIL_RETRY_DELAY_MS * (attempt + 1))
                }.isFailure
                if (interrupted || closed.get()) return null
            }
        }
        return null
    }

    private fun loadThumbnailSession(entry: MediaEntry): Bitmap? {
        if (closed.get()) return null
        val source = manager.openRandomAccess(profileId, entry.uri)
        val mediaSource = SmbMediaDataSource(source)
        activeSources.add(mediaSource)
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(mediaSource)
                val frame = runCatching {
                    retriever.getFrameAtTime(THUMBNAIL_TIME_US, OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: runCatching {
                    retriever.getFrameAtTime(0L, OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: return null
                scaleToFit(frame, thumbnailSizePx)
            }
        } finally {
            activeSources.remove(mediaSource)
            mediaSource.close()
        }
    }

    private fun loadMetadata(entry: MediaEntry): SmbVideoMetadata? {
        if (closed.get()) return null
        val source = manager.openRandomAccess(profileId, entry.uri)
        val mediaSource = SmbMediaDataSource(source)
        activeSources.add(mediaSource)
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(mediaSource)
                extractMetadata(retriever)
            }
        } finally {
            activeSources.remove(mediaSource)
            mediaSource.close()
        }
    }

    private fun scaleToFit(frame: Bitmap, maxSizePx: Int): Bitmap {
        val longestSide = maxOf(frame.width, frame.height)
        if (longestSide <= maxSizePx) return frame
        val scale = maxSizePx.toFloat() / longestSide
        val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    private fun extractMetadata(retriever: MediaMetadataRetriever): SmbVideoMetadata? {
        val rotation = retriever.extractMetadata(METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        var width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        var height = retriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val rotatedWidth = width
            width = height
            height = rotatedWidth
        }
        val durationMs = retriever.extractMetadata(METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        if (width <= 0 && height <= 0 && durationMs <= 0L) return null
        return SmbVideoMetadata(
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0L)
        )
    }

    private class MetadataEntry(val metadata: SmbVideoMetadata?)

    companion object {
        private const val THUMBNAIL_WORKERS = 2
        private const val THUMBNAIL_ATTEMPTS = 2
        private const val THUMBNAIL_RETRY_DELAY_MS = 400L
        private const val BITMAP_CACHE_BYTES = 12 * 1024 * 1024
        private const val METADATA_CACHE_ENTRIES = 512
        private const val THUMBNAIL_TIME_US = 1_000_000L
        private const val METADATA_KEY_VIDEO_WIDTH = MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        private const val METADATA_KEY_VIDEO_HEIGHT = MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        private const val METADATA_KEY_VIDEO_ROTATION =
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        private const val METADATA_KEY_DURATION = MediaMetadataRetriever.METADATA_KEY_DURATION
        private const val OPTION_CLOSEST_SYNC = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    }
}

private fun MediaEntry.mediaKey(): String = "$uri|$size|$modifiedTime"

private class SmbMediaDataSource(
    private val source: RandomAccessSource
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)
    private val readAheadBuffer = ByteArray(READ_AHEAD_BYTES)
    private var readAheadStart = -1L
    private var readAheadLength = 0

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) return -1
        if (size <= 0) return 0
        if (readAheadStart >= 0L &&
            position >= readAheadStart &&
            position + size <= readAheadStart + readAheadLength
        ) {
            System.arraycopy(
                readAheadBuffer,
                (position - readAheadStart).toInt(),
                buffer,
                offset,
                size
            )
            return size
        }
        if (size >= READ_AHEAD_BYTES) {
            return source.readAt(position, buffer, offset, size)
        }
        val read = source.readAt(position, readAheadBuffer, 0, READ_AHEAD_BYTES)
        if (read <= 0) return -1
        readAheadStart = position
        readAheadLength = read
        val copied = minOf(size, read)
        System.arraycopy(readAheadBuffer, 0, buffer, offset, copied)
        return copied
    }

    override fun getSize(): Long = source.size()

    override fun close() {
        if (closed.compareAndSet(false, true)) source.close()
    }

    private companion object {
        // Metadata parsing issues many small reads; serving them from one
        // network read removes most of the round-trip latency without
        // downloading much more of the file than the parser needs.
        const val READ_AHEAD_BYTES = 64 * 1024
    }
}
