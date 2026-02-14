package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


class CopyEngine(
    private val context: Context,
    private val lockManager: LockManager
) {

    private val walDir =
        File(context.filesDir, "copy_wal").apply { mkdirs() }

    private val jobRegistry =
        ConcurrentHashMap<String, CopyControl>()

    private var cachedSpeed: Long? = null

    private val WAL_SYNC_INTERVAL = 1_048_576L // 1MB

    // ============================================================
    // PUBLIC ENTRY
    // ============================================================

    suspend fun copyAdaptive(
        source: String,
        destinationParent: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null,
        forceProgress: Boolean = false
    ): CopyResult {

        val lockKey = "copy::$source->$destinationParent/$newName"

        return lockManager.withLock(lockKey) {

            val src = File(source)
            val parent = File(destinationParent)

            if (!src.exists() || !parent.exists()) {
                return@withLock CopyResult.Immediate(false)
            }

            val resolved = ConflictResolver.resolve(
                exists = { File(parent, it).exists() },
                baseName = newName,
                policy = conflictPolicy,
                manualRename = manualRename
            ) ?: return@withLock CopyResult.Immediate(false)

            val target = File(parent, resolved)
            val total = calculateSize(src)
            val threshold = adaptiveThreshold()

            val useProgress =
                forceProgress ||
                        src.isDirectory ||
                        total > threshold

            if (!useProgress) {
                val success = quickCopy(src, target)
                return@withLock CopyResult.Immediate(success)
            }

            val jobId = UUID.randomUUID().toString()
            val control = CopyControl()
            jobRegistry[jobId] = control

            val flow = copyTransactional(
                jobId,
                src,
                target,
                total,
                control
            )

            CopyResult.Transaction(jobId, flow)

        } ?: CopyResult.Immediate(false)
    }

    fun cancel(jobId: String): Boolean {
        val control = jobRegistry[jobId] ?: return false
        control.cancelled = true
        return true
    }

    fun pause(jobId: String): Boolean {
        val control = jobRegistry[jobId] ?: return false
        control.paused = true
        return true
    }

    fun resume(jobId: String): Boolean {
        val control = jobRegistry[jobId] ?: return false
        control.paused = false
        return true
    }

    fun isActive(jobId: String): Boolean =
        jobRegistry.containsKey(jobId)


    suspend fun recoverPendingCopies():
            List<Pair<String, Flow<OperationProgress>>> {

        val walFiles =
            walDir.listFiles { f -> f.extension == "wal" }
                ?: return emptyList()

        return walFiles.mapNotNull { file ->

            try {
                val tx = CopyTransaction.restore(walDir, file)

                val lockKey =
                    "copy::${tx.source.absolutePath}->${tx.target.absolutePath}"

                val result: Pair<String, Flow<OperationProgress>>? =
                    lockManager.withLock(lockKey) {

                        val control = CopyControl()
                        jobRegistry[tx.jobId] = control

                        val recoveryFlow: Flow<OperationProgress> = flow {

                            tx.execute(control) { copied, total ->

                                emit(
                                    OperationProgress(
                                        tx.source.absolutePath,
                                        tx.target.absolutePath,
                                        copied,
                                        total
                                    )
                                )
                            }

                            jobRegistry.remove(tx.jobId)
                        }

                        tx.jobId to recoveryFlow
                    }

                result

            } catch (_: Exception) {
                file.delete()
                null
            }
        }
    }


    // ============================================================
    // QUICK COPY
    // ============================================================

    private fun quickCopy(source: File, target: File): Boolean {
        return try {
            if (source.isDirectory) {
                copyDirectorySimple(source, target)
            } else {
                source.copyTo(target)
                validateChecksum(source, target)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    // TRANSACTIONAL COPY
    // ============================================================

    private fun copyTransactional(
        jobId: String,
        source: File,
        target: File,
        total: Long,
        control: CopyControl
    ): Flow<OperationProgress> = flow {

        val tx = CopyTransaction(
            walDir,
            jobId,
            source,
            target,
            total,
            WAL_SYNC_INTERVAL
        )

        tx.begin()

        tx.execute(control) { copied, totalBytes ->
            emit(
                OperationProgress(
                    source.absolutePath,
                    target.absolutePath,
                    copied,
                    totalBytes
                )
            )
        }

        jobRegistry.remove(jobId)
    }

    // ============================================================
    // UTILITIES
    // ============================================================

    private fun calculateSize(file: File): Long =
        if (file.isFile) file.length()
        else file.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun adaptiveThreshold(): Long {
        val speed = cachedSpeed ?: measureDiskSpeed()
        cachedSpeed = speed
        return (speed * 0.3).toLong()
    }

    private fun measureDiskSpeed(): Long {
        return try {
            val test = File(context.cacheDir, "speed.tmp")
            val size = 5 * 1024 * 1024
            val data = ByteArray(size)

            val start = System.nanoTime()
            FileOutputStream(test).use {
                it.write(data)
                it.flush()
                it.fd.sync()
            }
            val end = System.nanoTime()
            test.delete()

            val seconds = (end - start) / 1_000_000_000.0
            (size / seconds).toLong()
        } catch (_: Exception) {
            50L * 1024 * 1024
        }
    }

    private fun validateChecksum(source: File, target: File) {
        val a = sha256(source)
        val b = sha256(target)
        if (a != b) {
            target.delete()
            throw IllegalStateException("Checksum mismatch")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int

            while (true) {
                read = input.read(buffer)
                if (read == -1) break

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest()
            .joinToString("") { "%02x".format(it) }
    }


    private fun copyDirectorySimple(source: File, target: File): Boolean {
        if (!target.mkdirs() && !target.exists()) return false
        source.listFiles()?.forEach {
            val dest = File(target, it.name)
            if (it.isDirectory) {
                if (!copyDirectorySimple(it, dest)) return false
            } else {
                it.copyTo(dest)
                validateChecksum(it, dest)
            }
        }
        return true
    }

    // ============================================================
    // INTERNAL CLASSES
    // ============================================================

    private class CopyControl {
        @Volatile var cancelled = false
        @Volatile var paused = false
    }

    private class CopyTransaction(
        private val walDir: File,
        val jobId: String,
        val source: File,
        val target: File,
        private val totalBytes: Long,
        private val walSyncInterval: Long
    ) {

        private val walFile = File(walDir, "$jobId.wal")
        private var copiedBytes = 0L
        private var lastWalSync = 0L

        fun begin() {
            writeWal()
        }

        suspend fun execute(
            control: CopyControl,
            onProgress: suspend (Long, Long) -> Unit
        ) {

            if (source.isDirectory) {
                copyFolder(control, onProgress)
                if (!control.cancelled) {
                    validateFolderChecksum()
                    complete()
                }
                return
            }

            if (walFile.exists() && target.exists()) {
                copiedBytes = target.length().coerceAtMost(totalBytes)
            }

            var cancelled = false

            withContext(Dispatchers.IO) {

                RandomAccessFile(source, "r").use { input ->
                    RandomAccessFile(target, "rw").use { output ->

                        output.seek(copiedBytes)
                        input.seek(copiedBytes)

                        val buffer = ByteArray(512 * 1024)

                        while (true) {

                            if (control.cancelled) {
                                cancelled = true
                                break
                            }

                            while (control.paused) {
                                delay(100)
                            }

                            val read = input.read(buffer)
                            if (read == -1) break

                            output.write(buffer, 0, read)
                            copiedBytes += read

                            if (copiedBytes - lastWalSync >= walSyncInterval) {
                                writeWal()
                                lastWalSync = copiedBytes
                            }

                            onProgress(copiedBytes, totalBytes)
                        }
                    }
                }
            }

            if (cancelled) {
                cleanup()
                return
            }

            validateChecksum()
            complete()
        }

        private suspend fun copyFolder(
            control: CopyControl,
            onProgress: suspend (Long, Long) -> Unit
        ) {

            var global = copiedBytes

            val files =
                source.walkTopDown()
                    .filter { it.isFile }
                    .toList()

            files.forEach { file ->

                val relative = file.relativeTo(source).path
                val destFile = File(target, relative)

                destFile.parentFile?.mkdirs()

                FileInputStream(file).use { input ->
                    FileOutputStream(destFile).use { output ->

                        val buffer = ByteArray(512 * 1024)
                        var read: Int

                        while (true) {
                            read = input.read(buffer)
                            if (read == -1) break

                            if (control.cancelled) {
                                cleanup()
                                return
                            }

                            output.write(buffer, 0, read)
                            global += read
                            copiedBytes = global

                            if (copiedBytes - lastWalSync >= 1_048_576L) {
                                writeWal()
                                lastWalSync = copiedBytes
                            }

                            onProgress(global, totalBytes)
                        }
                    }
                }
            }
        }

        private fun validateFolderChecksum() {
            val srcSize = source.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }

            val dstSize = target.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }

            if (srcSize != dstSize) {
                cleanup()
                throw IllegalStateException("Folder size mismatch")
            }
        }

        private fun validateChecksum() {
            val srcHash = sha256(source)
            val dstHash = sha256(target)

            if (srcHash != dstHash) {
                cleanup()
                throw IllegalStateException("Checksum mismatch")
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read: Int

                while (true) {
                    read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun writeWal() {
            val json = JSONObject().apply {
                put("jobId", jobId)
                put("source", source.absolutePath)
                put("target", target.absolutePath)
                put("totalBytes", totalBytes)
                put("copiedBytes", copiedBytes)
                put("isDirectory", source.isDirectory)
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
            target.deleteRecursively()
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


        companion object {

            fun restore(
                walDir: File,
                walFile: File
            ): CopyTransaction {

                val json = JSONObject(walFile.readText())

                val tx = CopyTransaction(
                    walDir,
                    json.getString("jobId"),
                    File(json.getString("source")),
                    File(json.getString("target")),
                    json.getLong("totalBytes"),
                    1_048_576L
                )

                tx.copiedBytes = json.getLong("copiedBytes")
                return tx
            }
        }
    }
}
