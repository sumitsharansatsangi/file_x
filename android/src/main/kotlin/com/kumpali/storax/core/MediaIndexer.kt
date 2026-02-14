package com.kumpali.storax.core

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.util.Locale

class MediaIndexer(
    private val context: Context,
    private val enabled: Boolean = true
) {

    private val mediaExtensions = setOf(
        "jpg","jpeg","png","gif",
        "mp4","mkv","mp3","wav","webp"
    )

    fun scanIfMedia(file: File) {

        if (!enabled || !file.isFile) return

        val ext = file.extension.lowercase(Locale.ROOT)

        if (ext in mediaExtensions) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null
            )
        }
    }

    fun scanDeleted(path: String) {
        if (!enabled) return

        MediaScannerConnection.scanFile(
            context,
            arrayOf(path),
            null,
            null
        )
    }
}
