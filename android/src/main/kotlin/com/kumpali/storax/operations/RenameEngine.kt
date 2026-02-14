package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.BackendDetector
import com.kumpali.storax.core.ConflictPolicy
import com.kumpali.storax.core.LockManager
import com.kumpali.storax.core.MediaIndexer
import com.kumpali.storax.manager.JournalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RenameEngine(
    private val context: Context,
    private val lockManager: LockManager,
    private val journalManager: JournalManager,
    private val mediaIndexer: MediaIndexer
) {

    suspend fun rename(
        source: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): Boolean = withContext(Dispatchers.IO) {

        require(source.isNotBlank()) { "Source cannot be empty" }
        require(newName.isNotBlank()) { "New name cannot be empty" }

        val backend = BackendDetector.detect(context, source,mediaIndexer)

        val lockKey = "rename::$source"

        lockManager.withLock(lockKey) {

            // 1️⃣ Begin durable journal entry
            val journalFile = journalManager.beginRename(source, newName)

            try {

                // 2️⃣ Perform actual rename
                val success = backend.rename(
                    source = source,
                    newName = newName,
                    conflictPolicy = conflictPolicy,
                    manualRename = manualRename
                )

                if (!success) {
                    // Leave journal for recovery
                    return@withLock false
                }

                // 3️⃣ Mark committed (fsync-safe)
                journalManager.markCompleted(journalFile)

                // 4️⃣ Remove journal (final cleanup)
                journalManager.remove(journalFile)

                true

            } catch (_: Exception) {
                // Journal remains → recovery will fix
                false
            }

        } ?: false
    }
}
