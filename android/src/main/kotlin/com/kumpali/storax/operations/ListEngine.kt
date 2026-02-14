package com.kumpali.storax.operations

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kumpali.storax.core.NodeInfo
import java.io.File

class ListEngine(
    private val context: Context
) {

    suspend fun list(path: String): List<NodeInfo> =
        withContext(Dispatchers.IO) {

            if (path.isBlank()) return@withContext emptyList()

            if (path.startsWith("content://")) {
                listSaf(path)
            } else {
                listNative(path)
            }
        }

    // ============================================================
    // ====================== NATIVE LIST =========================
    // ============================================================

    private fun listNative(path: String): List<NodeInfo> {

        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.mapNotNull { file ->
            runCatching {
                NodeInfo(
                    name = file.name,
                    pathOrUri = file.absolutePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified()
                )
            }.getOrNull()
        } ?: emptyList()
    }

    // ============================================================
    // ======================= SAF LIST ===========================
    // ============================================================

    private fun listSaf(path: String): List<NodeInfo> {

        val uri = path.toUri()

        val dir = if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        } ?: return emptyList()

        if (!dir.isDirectory) return emptyList()

        return dir.listFiles().mapNotNull { doc ->
            runCatching {
                NodeInfo(
                    name = doc.name ?: "",
                    pathOrUri = doc.uri.toString(),
                    isDirectory = doc.isDirectory,
                    size = doc.length(),
                    lastModified = doc.lastModified()
                )
            }.getOrNull()
        }
    }
}
