package com.kumpali.storax.manager

import android.content.Context
import com.kumpali.storax.core.*
import com.kumpali.storax.operations.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class StorageManager(
    private val context: Context,
    private val journalManager: JournalManager,
    private val mediaIndexer: MediaIndexer
) {

    private val lockManager = LockManager()

    private val listEngine = ListEngine(context)
    private val undoEngine = UndoEngine(context)

    private val trashManager = TrashManager(context)

    private val createEngine =
        CreateEngine(context, lockManager, journalManager, mediaIndexer)

    private val renameEngine =
        RenameEngine(context, lockManager, journalManager,mediaIndexer)

    private val copyEngine =
        CopyEngine(context, lockManager)

    private val moveEngine =
        MoveEngine(context, lockManager, copyEngine, mediaIndexer)

    private val deleteEngine =
        DeleteEngine(context, lockManager, trashManager, mediaIndexer)

    private val traverseEngine = TraverseEngine(context)



    suspend fun list(path: String): List<Map<String, Any?>> {

        val nodes = listEngine.list(path)

        return nodes.map { node ->
            mapOf(
                "name" to node.name,
                "path" to if (node.pathOrUri.startsWith("content://")) null else node.pathOrUri,
                "uri" to if (node.pathOrUri.startsWith("content://")) node.pathOrUri else null,
                "isDirectory" to node.isDirectory,
                "size" to node.size,
                "lastModified" to node.lastModified
            )
        }
    }

    suspend fun traverse(
        rootPath: String,
        maxDepth: Int
    ): List<Map<String, Any?>> {

        val nodes = traverseEngine.traverse(rootPath, maxDepth)

        return nodes.map { node ->
            mapOf(
                "name" to node.name,
                "path" to if (node.pathOrUri.startsWith("content://")) null else node.pathOrUri,
                "uri" to if (node.pathOrUri.startsWith("content://")) node.pathOrUri else null,
                "isDirectory" to node.isDirectory,
                "size" to node.size,
                "lastModified" to node.lastModified
            )
        }
    }


    // ============================================================
    // CREATE
    // ============================================================

    suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): NodeResult {

        val result = createEngine.create(
            parent, name, type, conflictPolicy, manualRename
        )

        if (result.success && result.pathOrUri != null) {
            undoEngine.register(
                UndoAction.Create(result.pathOrUri, type)
            )
        }

        return result
    }

    // ============================================================
    // RENAME
    // ============================================================

    suspend fun rename(
        source: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): Boolean {

        val parent = File(source).parent ?: return false

        val success = renameEngine.rename(
            source, newName, conflictPolicy, manualRename
        )

        if (success) {
            undoEngine.register(
                UndoAction.Rename(
                    from = "$parent/$newName",
                    to = source
                )
            )
        }

        return success
    }

    // ============================================================
    // DELETE (TRASH-BASED)
    // ============================================================

    suspend fun delete(path: String): Boolean {

        val file = File(path)
        if (!file.exists()) return false

        val success = trashManager.moveToTrash(path)
        if (!success) return false

        // We need the stored entry to get trashedPath
        val entry = trashManager.list()
            .lastOrNull { it.originalPath == path }
            ?: return false

        undoEngine.register(
            UndoAction.Delete(
                originalPath = entry.originalPath!!,
                backupPath = entry.trashedPath!!
            )
        )

        return true
    }

    // ============================================================
    // MOVE
    // ============================================================

    suspend fun move(
        source: String,
        destParent: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): Boolean {

        return when (val result =
            moveEngine.move(
                source,
                destParent,
                newName,
                conflictPolicy,
                manualRename
            )
        ) {

            is CopyResult.Immediate -> {

                if (result.success) {
                    undoEngine.register(
                        UndoAction.Move(
                            from = "$destParent/$newName",
                            to = source
                        )
                    )
                }

                result.success
            }

            is CopyResult.Transaction -> false
        }
    }

    // ============================================================
    // COPY
    // ============================================================

    suspend fun copy(
        source: String,
        destParent: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): CopyResult {

        return when (val result =
            copyEngine.copyAdaptive(
                source,
                destParent,
                newName,
                conflictPolicy,
                manualRename
            )
        ) {

            is CopyResult.Immediate -> {

                if (result.success) {
                    undoEngine.register(
                        UndoAction.Copy("$destParent/$newName")
                    )
                }

                result
            }

            is CopyResult.Transaction -> {

                val wrapped = flow {
                    result.flow.collect { emit(it) }

                    undoEngine.register(
                        UndoAction.Copy("$destParent/$newName")
                    )
                }

                CopyResult.Transaction(result.jobId, wrapped)
            }
        }
    }

    fun cancelCopy(jobId: String?): Boolean {

        if (jobId.isNullOrBlank()) return false

        if (!copyEngine.isActive(jobId)) {
            return false
        }

        return copyEngine.cancel(jobId)
    }

    fun pauseCopy(jobId: String?): Boolean {

        if (jobId.isNullOrBlank()) return false

        if (!copyEngine.isActive(jobId)) {
            return false
        }

        return copyEngine.pause(jobId)
    }

    fun resumeCopy(jobId: String?): Boolean {

        if (jobId.isNullOrBlank()) return false

        if (!copyEngine.isActive(jobId)) {
            return false
        }

        return copyEngine.resume(jobId)
    }


    // ============================================================
    // RECOVERY
    // ============================================================

    suspend fun recoverPendingOperations():
            List<Pair<String, Flow<OperationProgress>>> {

        journalManager.recoverPendingOperations {
            BackendDetector.detect(context, it, mediaIndexer)
        }

        return copyEngine.recoverPendingCopies() +
                moveEngine.recoverPendingMoves()
    }

    // ============================================================
    // UNDO
    // ============================================================

    suspend fun undo(): Boolean {

        return undoEngine.undoLast { action ->

            when (action) {

                is UndoAction.Create ->
                    trashManager.moveToTrash(action.path)

                is UndoAction.Rename ->
                    renameEngine.rename(
                        source = action.from,
                        newName = File(action.to).name,
                        conflictPolicy = ConflictPolicy.FAIL
                    )

                is UndoAction.Move ->
                    moveEngine.move(
                        source = action.from,
                        destinationParent = File(action.to).parent!!,
                        newName = File(action.to).name,
                        conflictPolicy = ConflictPolicy.FAIL
                    ) is CopyResult.Immediate

                is UndoAction.Copy ->
                    trashManager.moveToTrash(action.path)

                is UndoAction.Delete -> {

                    val entry = TrashEntry(
                        id = "",
                        name = File(action.originalPath).name,
                        isSaf = false,
                        isDirectory = File(action.backupPath).isDirectory,
                        trashedAt = 0,
                        size = null,
                        originalPath = action.originalPath,
                        trashedPath = action.backupPath
                    )

                    trashManager.restore(entry)
                }
            }
        }
    }

    // ============================================================
    // REDO
    // ============================================================

    suspend fun redo(): Boolean {

        return undoEngine.redoLast { action ->

            when (action) {

                is UndoAction.Create ->
                    createEngine.recreate(action.path, type = action.type)

                is UndoAction.Rename ->
                    renameEngine.rename(
                        source = action.to,
                        newName = File(action.from).name,
                        conflictPolicy = ConflictPolicy.FAIL
                    )

                is UndoAction.Move ->
                    moveEngine.move(
                        source = action.to,
                        destinationParent = File(action.from).parent!!,
                        newName = File(action.from).name,
                        conflictPolicy = ConflictPolicy.FAIL
                    ) is CopyResult.Immediate

                is UndoAction.Copy ->
                    false

                is UndoAction.Delete ->
                    trashManager.moveToTrash(action.originalPath)
            }
        }
    }

    suspend fun canUndo() = undoEngine.canUndo()
    suspend fun canRedo() = undoEngine.canRedo()
    suspend fun clearUndo() = undoEngine.clear()
    suspend fun undoCount() = undoEngine.undoCount()
    suspend fun redoCount() = undoEngine.redoCount()

    suspend fun restoreFromTrash(entry: TrashEntry): Boolean {

        val success = trashManager.restore(entry)

        if (success) {
            undoEngine.register(
                UndoAction.Delete(
                    originalPath = entry.originalPath!!,
                    backupPath = entry.trashedPath!!
                )
            )
        }

        return success
    }

    suspend fun permanentlyDeleteFromTrash(
        entry: TrashEntry
    ): Boolean {

        return deleteEngine.permanentlyDeleteFromTrash(entry)
    }

    suspend fun emptyTrash(): Boolean {

        val entries = trashManager.list()

        var allSuccess = true

        for (entry in entries) {
            val success =
                deleteEngine.permanentlyDeleteFromTrash(entry)

            if (!success) {
                allSuccess = false
            }
        }

        return allSuccess
    }

    suspend fun listTrash(): List<TrashEntry> =
        trashManager.list()

    suspend fun permanentlyDelete(path: String): Boolean =
        deleteEngine.permanentlyDelete(path)


}
