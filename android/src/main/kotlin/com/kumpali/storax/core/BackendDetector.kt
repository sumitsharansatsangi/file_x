package com.kumpali.storax.core

import android.content.Context
import androidx.core.net.toUri
import com.kumpali.storax.backends.NativeBackend
import com.kumpali.storax.backends.SafBackend

object BackendDetector {

    fun detect(context: Context, location: String,   mediaIndexer: MediaIndexer): StorageBackend {

        val appContext = context.applicationContext

        return if (isSaf(location)) {
            SafBackend(appContext)
        } else {
            NativeBackend(appContext, mediaIndexer)
        }
    }

    private fun isSaf(path: String): Boolean =
        try {
            path.toUri().scheme == "content"
        } catch (_: Exception) {
            false
        }
}
