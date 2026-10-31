package com.vr2sbs.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vr2sbs.smb.RandomAccessSource
import com.vr2sbs.smb.SmbClientManager
import com.vr2sbs.source.SourceDescriptor
import com.vr2sbs.source.SourceType
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Loads a still image from any supported source and downsamples it to a size
 * the renderer can upload as a single OpenGL texture.
 */
object VrImageLoader {
    /**
     * Every GLES 2 device supports at least 2048 px textures; 4096 covers
     * common VR180 photos while keeping GPU memory reasonable.
     */
    const val MAX_TEXTURE_DIMENSION = 4096

    private const val MAX_IMAGE_BYTES = 128L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val SBS_MIN_ASPECT_RATIO = 1.5
    private const val SBS_MAX_MEAN_DIFFERENCE = 12.0
    private const val SBS_SAMPLE_GRID = 32

    fun load(
        context: Context,
        smbClientManager: SmbClientManager,
        source: SourceDescriptor,
        maxDimension: Int = MAX_TEXTURE_DIMENSION
    ): LoadedImage {
        val bytes = readImageBytes(context.applicationContext, smbClientManager, source)
        val bitmap = decodeSampled(bytes, maxDimension)
        val oriented = applyExifOrientation(bytes, bitmap)
        return LoadedImage(
            bitmap = oriented,
            sideBySide = isSideBySideStereo(oriented)
        )
    }

    /**
     * VR180 stills are commonly stored as two near-identical eye views packed
     * side by side. Treat wide images whose halves match closely as a stereo
     * pair so each eye receives its own half; other photos are shown in full
     * to both eyes.
     */
    private fun isSideBySideStereo(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width < SBS_SAMPLE_GRID * 2 || height < SBS_SAMPLE_GRID) return false
        val halfWidth = width / 2
        if (halfWidth <= 0) return false

        var totalDifference = 0L
        var samples = 0
        for (row in 0 until SBS_SAMPLE_GRID) {
            val y = ((row + 0.5) * height / SBS_SAMPLE_GRID).toInt().coerceIn(0, height - 1)
            for (column in 0 until SBS_SAMPLE_GRID) {
                val x = ((column + 0.5) * halfWidth / SBS_SAMPLE_GRID)
                    .toInt()
                    .coerceIn(0, halfWidth - 1)
                totalDifference += abs(
                    luminance(bitmap.getPixel(x, y)) -
                        luminance(bitmap.getPixel(halfWidth + x, y))
                )
                samples += 1
            }
        }
        val meanDifference = totalDifference.toDouble() / samples
        return isSideBySideStereo(
            width = width,
            height = height,
            meanLumaDifference = meanDifference
        )
    }

    internal fun isSideBySideStereo(
        width: Int,
        height: Int,
        meanLumaDifference: Double
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width.toDouble() / height < SBS_MIN_ASPECT_RATIO) return false
        return meanLumaDifference <= SBS_MAX_MEAN_DIFFERENCE
    }

    private fun luminance(color: Int): Int {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun readImageBytes(
        context: Context,
        smbClientManager: SmbClientManager,
        source: SourceDescriptor
    ): ByteArray = when (source.type) {
        SourceType.SMB_URI -> {
            val profileId = requireNotNull(source.profileId) {
                "SMB image source is missing its profile"
            }
            smbClientManager.openRandomAccess(profileId, source.normalized).use { readBytes(it) }
        }

        SourceType.LOCAL_URI -> {
            val stream = context.contentResolver.openInputStream(Uri.parse(source.normalized))
                ?: throw IOException("Unable to open image: ${source.normalized}")
            BufferedInputStream(stream, BUFFER_BYTES).use { readBytes(it) }
        }

        SourceType.HTTP_URL -> {
            val connection = (URL(source.normalized).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("HTTP $status while loading image")
                }
                BufferedInputStream(connection.inputStream, BUFFER_BYTES).use { readBytes(it) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBytes(source: RandomAccessSource): ByteArray {
        val size = source.size()
        if (size <= 0L) throw IOException("Image is empty")
        if (size > MAX_IMAGE_BYTES) throw IOException("Image is too large")
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = source.readAt(
                offset.toLong(),
                bytes,
                offset,
                minOf(BUFFER_BYTES, bytes.size - offset)
            )
            if (read <= 0) throw IOException("Unexpected end of image")
            offset += read
        }
        return bytes
    }

    private fun readBytes(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMAGE_BYTES) throw IOException("Image is too large")
            output.write(buffer, 0, read)
        }
        if (total == 0L) throw IOException("Image is empty")
        return output.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap {
        val safeMaxDimension = maxDimension.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported image format")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = safeMaxDimension
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Unable to decode image")
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        val transformed = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { return bitmap }
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }
}

data class LoadedImage(
    val bitmap: Bitmap,
    val sideBySide: Boolean
)
