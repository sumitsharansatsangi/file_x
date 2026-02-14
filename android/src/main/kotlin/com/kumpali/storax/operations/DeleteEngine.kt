package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.BackendDetector
import com.kumpali.storax.core.LockManager
import com.kumpali.storax.core.MediaIndexer
import com.kumpali.storax.core.TrashEntry
import com.kumpali.storax.manager.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeleteEngine(
    private val context: Context,
    private val lockManager: LockManager,
    private val trashManager: TrashManager,
    private val mediaIndexer: MediaIndexer,
) {

    // ============================================================
    // PERMANENT DELETE (NORMAL FILE)
    // ============================================================

    suspend fun permanentlyDelete(path: String): Boolean =
        withContext(Dispatchers.IO) {

            require(path.isNotBlank()) { "Path cannot be empty" }

            val backend = BackendDetector.detect(context, path, mediaIndexer)
            val lockKey = "permanent_delete::$path"

            lockManager.withLock(lockKey) {

                backend.delete(path)

            } ?: false
        }

    // ============================================================
    // PERMANENT DELETE FROM TRASH
    // ============================================================

    suspend fun permanentlyDeleteFromTrash(
        entry: TrashEntry
    ): Boolean = withContext(Dispatchers.IO) {

        val trashedPath = entry.trashedPath ?: return@withContext false

        val lockKey = "trash_delete::$trashedPath"

        lockManager.withLock(lockKey) {

            trashManager.permanentlyDeleteEntry(entry)

        } ?: false
    }

}
