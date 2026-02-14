package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.*
import com.kumpali.storax.manager.JournalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CreateEngine(
    private val context: Context,
    private val lockManager: LockManager,
    private val journalManager: JournalManager,
    private val mediaIndexer: MediaIndexer,
) {

    // ============================================================
    // NORMAL CREATE
    // ============================================================

    suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): NodeResult = withContext(Dispatchers.IO) {

        require(parent.isNotBlank()) { "Parent cannot be empty" }
        require(name.isNotBlank()) { "Name cannot be empty" }

        val backend = BackendDetector.detect(context, parent, mediaIndexer)
        val lockKey = "create::$parent/$name"

        lockManager.withLock(lockKey) {

            val journal = journalManager.beginCreate(
                parent,
                name,
                type,
                conflictPolicy
            )

            try {

                val result = backend.create(
                    parent = parent,
                    name = name,
                    type = type,
                    conflictPolicy = conflictPolicy,
                    manualRename = manualRename
                )

                if (!result.success) {
                    return@withLock result
                }

                journalManager.markCompleted(journal)
                journalManager.remove(journal)

                result

            } catch (_: Exception) {
                NodeResult(
                    success = false,
                    pathOrUri = null,
                    error = "Create failed"
                )
            }

        } ?: NodeResult(false, null, "Lock timeout")
    }

    // ============================================================
    // RECREATE (Used by REDO)
    // ============================================================

    suspend fun recreate(
        path: String,
        type: NodeType
    ): Boolean = withContext(Dispatchers.IO) {

        val file = File(path)

        // Idempotent behavior
        if (file.exists()) return@withContext true

        val parent = file.parent ?: return@withContext false
        val name = file.name

        val result = create(
            parent = parent,
            name = name,
            type = type,
            conflictPolicy = ConflictPolicy.FAIL,
            manualRename = null
        )

        result.success
    }
}
