package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

class MoveEngine(
    private val context: Context,
    private val lockManager: LockManager,
    private val copyEngine: CopyEngine,
    private val mediaIndexer: MediaIndexer,
) {

    private val walDir =
        File(context.filesDir, "move_wal").apply { mkdirs() }

    // ============================================================
    // MOVE ENTRY
    // ============================================================

    suspend fun move(
        source: String,
        destinationParent: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): CopyResult {

        val lockKey = "move::$source->$destinationParent/$newName"

        return lockManager.withLock(lockKey) {

            val sourceBackend = BackendDetector.detect(context, source, mediaIndexer)
            val destBackend = BackendDetector.detect(context, destinationParent, mediaIndexer)

            // Same backend → simple rename
            if (sourceBackend.type == destBackend.type) {
                val success = sourceBackend.rename(
                    source,
                    newName,
                    conflictPolicy,
                    manualRename
                )
                return@withLock CopyResult.Immediate(success)
            }

            // Cross backend → WAL backed transaction
            val jobId = UUID.randomUUID().toString()
            val resolved = ConflictResolver.resolve(
                exists = { File(destinationParent, it).exists() },
                baseName = newName,
                policy = conflictPolicy,
                manualRename = manualRename
            ) ?: return@withLock CopyResult.Immediate(false)

            val targetPath = File(destinationParent, resolved).absolutePath


            val tx = MoveTransaction(
                walDir,
                jobId,
                source,
                targetPath,
                copyEngine,
                sourceBackend
            )

            tx.begin()

            CopyResult.Transaction(jobId, tx.execute())

        } ?: CopyResult.Immediate(false)
    }

    // ============================================================
    // RECOVERY ENTRY
    // ============================================================

    suspend fun recoverPendingMoves():
            List<Pair<String, Flow<OperationProgress>>> {

        val walFiles =
            walDir.listFiles { f -> f.extension == "wal" }
                ?: return emptyList()

        return walFiles.mapNotNull { walFile ->

            try {
                val json = JSONObject(walFile.readText())

                val jobId = json.getString("jobId")
                val source = json.getString("source")

                val sourceBackend =
                    BackendDetector.detect(context, source, mediaIndexer)

                val lockKey = "move::$source"

                val result: Pair<String, Flow<OperationProgress>>? =
                    lockManager.withLock(lockKey) {

                        MoveTransaction.recover(
                            walFile,
                            copyEngine,
                            sourceBackend
                        )?.let { flow ->
                            jobId to flow
                        }
                    }

                result

            } catch (_: Exception) {
                walFile.delete()
                null
            }
        }
    }

    // ============================================================
    // MOVE TRANSACTION
    // ============================================================

    private class MoveTransaction(
        private val walDir: File,
        val jobId: String,
        private val sourcePath: String,
        private val targetPath: String,
        private val copyEngine: CopyEngine,
        private val sourceBackend: StorageBackend
    ) {

        private val walFile = File(walDir, "$jobId.wal")

        private enum class Phase { COPYING, DELETING }

        // ----------------------------
        // BEGIN
        // ----------------------------

        fun begin() = writeWal(Phase.COPYING)

        // ----------------------------
        // EXECUTE
        // ----------------------------

        fun execute(): Flow<OperationProgress> = flow {

            // -------- COPY PHASE --------

            when (val copyResult = copyEngine.copyAdaptive(
                source = sourcePath,
                destinationParent = File(targetPath).parent!!,
                newName = File(targetPath).name,
                conflictPolicy = ConflictPolicy.REPLACE
            )) {

                is CopyResult.Immediate -> {
                    if (!copyResult.success) {
                        cleanup()
                        throw IllegalStateException("Move failed: copy phase failed")
                    }
                }

                is CopyResult.Transaction -> {
                    copyResult.flow.collect { emit(it) }
                }
            }

            // -------- DELETE PHASE --------

            writeWal(Phase.DELETING)

            val deleted = sourceBackend.delete(sourcePath)

            if (!deleted) {
                File(targetPath).deleteRecursively()
                cleanup()
                throw IllegalStateException("Move failed: delete phase failed")
            }

            complete()
        }

        // ----------------------------
        // WAL
        // ----------------------------

        private fun writeWal(phase: Phase) {

            val json = JSONObject().apply {
                put("jobId", jobId)
                put("source", sourcePath)
                put("destination", targetPath)
                put("phase", phase.name)
            }

            val temp = File(walFile.parentFile, "$jobId.tmp")

            FileOutputStream(temp).use {
                it.write(json.toString().toByteArray())
                it.flush()
                it.fd.sync()
            }

            temp.renameTo(walFile)
            fsyncDir()
        }

        private fun complete() {
            walFile.delete()
            fsyncDir()
        }

        private fun cleanup() {
            walFile.delete()
            fsyncDir()
        }

        private fun fsyncDir() {
            try {
                val raf = RandomAccessFile(walDir, "r")
                raf.fd.sync()
                raf.close()
            } catch (_: Exception) {
                // Best effort only
            }
        }


        // ----------------------------
        // RECOVERY (Single Source Of Truth)
        // ----------------------------

        companion object {

            suspend fun recover(
                walFile: File,
                copyEngine: CopyEngine,
                sourceBackend: StorageBackend
            ): Flow<OperationProgress>? {

                val json = JSONObject(walFile.readText())

                val source = json.getString("source")
                val destination = json.getString("destination")
                val phase = json.getString("phase")

                return when (phase) {

                    Phase.COPYING.name -> {

                        when (val result = copyEngine.copyAdaptive(
                            source = source,
                            destinationParent = File(destination).parent!!,
                            newName = File(destination).name,
                            conflictPolicy = ConflictPolicy.REPLACE
                        )) {
                            is CopyResult.Transaction -> result.flow
                            else -> null
                        }
                    }

                    Phase.DELETING.name -> {

                        flow {

                            val deleted =
                                sourceBackend.delete(source)

                            if (!deleted) {
                                throw IllegalStateException(
                                    "Move recovery delete retry failed"
                                )
                            }

                            walFile.delete()
                        }
                    }

                    else -> null
                }
            }
        }
    }
}
