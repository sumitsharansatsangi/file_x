package com.kumpali.storax.backends

import android.content.Context
import com.kumpali.storax.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class NativeBackend(
    private val context: Context,
    private val mediaIndexer: MediaIndexer
) : StorageBackend {

    override val type: String = "native"

    // ============================================================
    // ======================= CREATE ==============================
    // ============================================================

    override suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): NodeResult = withContext(Dispatchers.IO) {

        val parentFile = File(parent)

        if (!parentFile.exists() || !parentFile.isDirectory)
            return@withContext NodeResult(false, error = "Invalid parent directory")

        val resolvedName = ConflictResolver.resolve(
            exists = { File(parentFile, it).exists() },
            baseName = name,
            policy = conflictPolicy,
            manualRename = manualRename
        ) ?: return@withContext NodeResult(false, error = "Already exists")

        val target = File(parentFile, resolvedName)

        val created = when (type) {
            NodeType.DIRECTORY -> target.mkdirs()
            NodeType.FILE -> {
                try {
                    target.createNewFile()
                } catch (_: Exception) {
                    false
                }
            }
        }

        if (!created && !target.exists())
            return@withContext NodeResult(false, error = "Creation failed")

        // Index only media files
        if (type == NodeType.FILE) {
            mediaIndexer.scanIfMedia(target)
        }

        NodeResult(true, resolvedName, target.absolutePath)
    }

    // ============================================================
    // ======================= DELETE ==============================
    // ============================================================

    override suspend fun delete(path: String): Boolean =
        withContext(Dispatchers.IO) {

            val node = File(path)

            if (!node.exists()) return@withContext false

            val deleted = try {
                if (node.isDirectory) {
                    node.deleteRecursively()
                } else {
                    node.delete()
                }
            } catch (e: Exception) {
                false
            }

            if (deleted) {
                // Inform MediaStore about removal (file OR directory)
                mediaIndexer.scanDeleted(path)
            }

            deleted
        }


    // ============================================================
    // ======================= RENAME ==============================
    // ============================================================

    override suspend fun rename(
        source: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): Boolean = withContext(Dispatchers.IO) {

        val file = File(source)
        if (!file.exists()) return@withContext false

        val parent = file.parentFile ?: return@withContext false

        val resolvedName = ConflictResolver.resolve(
            exists = { File(parent, it).exists() },
            baseName = newName,
            policy = conflictPolicy,
            manualRename = manualRename
        ) ?: return@withContext false

        val target = File(parent, resolvedName)

        val success = file.renameTo(target)

        if (success && target.isFile) {
            mediaIndexer.scanIfMedia(target)
        }

        success
    }
}
