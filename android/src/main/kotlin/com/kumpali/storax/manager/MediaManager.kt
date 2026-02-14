package com.kumpali.storax.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.math.min

class MediaManager(private val context: Context) {

    companion object {
        private const val MAX_FRAME_COUNT = 30
        private const val MAX_OUTPUT_SIZE = 1920
        private const val TIMEOUT_MS = 8000L
        private const val JPEG_QUALITY = 80
    }

    suspend fun generateVideoThumbnails(
        videoPath: String,
        width: Int? = null,
        height: Int? = null,
        requestedFrameCount: Int
    ): List<ByteArray> = withTimeout(TIMEOUT_MS) {

        require(videoPath.isNotBlank())
        require(requestedFrameCount > 0)

        val frameCount = min(requestedFrameCount, MAX_FRAME_COUNT)
        val retriever = MediaMetadataRetriever()

        try {
            setDataSource(retriever, videoPath)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return@withTimeout emptyList()

            if (durationMs <= 0) return@withTimeout emptyList()

            val intervalUs = (durationMs * 1000) / frameCount
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            // Device-dependent concurrency
            val availableCores = Runtime.getRuntime().availableProcessors()
            val concurrencyLevel = min(frameCount, availableCores)

            coroutineScope {
                (0 until frameCount).map { i ->
                    async {
                        val timeUs = i * intervalUs
                        val rawBitmap = retriever.getFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        ) ?: return@async null

                        val rotated = if (rotation != 0) rotateBitmap(rawBitmap, rotation) else rawBitmap
                        val processed = processBitmap(rotated, width, height)

                        val bytes = bitmapToBytes(processed)

                        if (processed != rotated) processed.recycle()
                        if (rotated != rawBitmap) rotated.recycle()
                        rawBitmap.recycle()

                        bytes
                    }
                }
                    .chunked(concurrencyLevel)
                    .flatMap { chunk -> chunk.mapNotNull { it.await() } }
            }
        } finally {
            retriever.release()
        }
    }

    fun isHevc(videoPath: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, videoPath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.contains("hevc", ignoreCase = true) == true
        } finally {
            retriever.release()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun processBitmap(bitmap: Bitmap, width: Int?, height: Int?): Bitmap {
        val limitedWidth = width?.let { min(it, MAX_OUTPUT_SIZE) }
        val limitedHeight = height?.let { min(it, MAX_OUTPUT_SIZE) }

        if (limitedWidth == null && limitedHeight == null) return bitmap

        val srcWidth = bitmap.width
        val srcHeight = bitmap.height

        val targetWidth: Int
        val targetHeight: Int

        when {
            limitedWidth != null && limitedHeight != null -> {
                targetWidth = limitedWidth
                targetHeight = limitedHeight
            }
            limitedWidth != null -> {
                targetWidth = limitedWidth
                targetHeight = (limitedWidth * srcHeight) / srcWidth
            }
            limitedHeight != null -> {
                targetHeight = limitedHeight
                targetWidth = (limitedHeight * srcWidth) / srcHeight
            }
            else -> return bitmap
        }

        return bitmap.scale(targetWidth, targetHeight)
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }

    private fun setDataSource(retriever: MediaMetadataRetriever, videoPath: String) {
        if (videoPath.startsWith("content://")) {
            retriever.setDataSource(context, videoPath.toUri())
        } else {
            retriever.setDataSource(videoPath)
        }
    }
}
