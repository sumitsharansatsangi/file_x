package com.kumpali.storax.backends

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.kumpali.storax.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafBackend(
    private val context: Context
) : StorageBackend {

    override val type: String = "saf"

    override suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): NodeResult = withContext(Dispatchers.IO) {

        val parentDoc = getDocument(parent)
            ?: return@withContext NodeResult(false, error = "Invalid SAF location")

        if (!parentDoc.canWrite())
            return@withContext NodeResult(false, error = "No write permission")

        val resolvedName = ConflictResolver.resolve(
            exists = { parentDoc.findFile(it) != null },
            baseName = name,
            policy = conflictPolicy,
            manualRename = manualRename
        ) ?: return@withContext NodeResult(false, error = "Already exists")

        val created = when (type) {
            NodeType.DIRECTORY ->
                parentDoc.createDirectory(resolvedName)

            NodeType.FILE ->
                parentDoc.createFile("application/octet-stream", resolvedName)
        } ?: return@withContext NodeResult(false, error = "Creation failed")

        NodeResult(true, resolvedName, created.uri.toString())
    }

    override suspend fun delete(path: String): Boolean =
        withContext(Dispatchers.IO) {

            val document = getDocument(path) ?: return@withContext false

            return@withContext try {
                document.delete()
            } catch (e: Exception) {
                false
            }
        }



    override suspend fun rename(
        source: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): Boolean = withContext(Dispatchers.IO) {

        val doc = getDocument(source) ?: return@withContext false
        val parent = doc.parentFile ?: return@withContext false

        val resolvedName = ConflictResolver.resolve(
            exists = { parent.findFile(it) != null },
            baseName = newName,
            policy = conflictPolicy,
            manualRename = manualRename
        ) ?: return@withContext false

        // 🚀 Avoid unnecessary rename
        if (doc.name == resolvedName) return@withContext true

        try {
            val newUri = DocumentsContract.renameDocument(
                context.contentResolver,
                doc.uri,
                resolvedName
            ) ?: return@withContext false

            // 🔎 Rebuild DocumentFile using new URI
            val renamedDoc = DocumentFile.fromSingleUri(context, newUri)
                ?: return@withContext false

            // 🛡 Optional verification
            renamedDoc.name == resolvedName

        } catch (e: Exception) {
            false
        }
    }

    private fun getDocument(path: String): DocumentFile? {
        val uri = path.toUri()
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }
}
