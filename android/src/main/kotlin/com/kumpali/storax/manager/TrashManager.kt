package com.kumpali.storax.manager

import android.content.Context
import com.kumpali.storax.core.TrashEntry
import com.kumpali.storax.core.TrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class TrashManager(
    private val context: Context,
    private val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000,  // 30 days
    private val maxSizeBytes: Long = 5L * 1024 * 1024 * 1024     // 5 GB default
) {

    private val store = TrashStore(context)

    private fun nativeTrashDir(): File {
        val dir = File(context.getExternalFilesDir(null), ".storax_trash")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ===========================
    // MOVE TO TRASH
    // ===========================

    suspend fun moveToTrash(path: String): Boolean =
        withContext(Dispatchers.IO) {

            val file = File(path)
            if (!file.exists()) return@withContext false

            val trashDir = nativeTrashDir()
            val id = UUID.randomUUID().toString()
            val trashed = File(trashDir, "${id}_${file.name}")


            val size = if (file.isFile) file.length() else 0L

            if (!file.renameTo(trashed)) {
                file.copyRecursively(trashed, true)
                file.deleteRecursively()
            }

            store.add(
                TrashEntry(
                    id = id,
                    name = file.name,
                    isSaf = false,
                    isDirectory = file.isDirectory,
                    trashedAt = System.currentTimeMillis(),
                    size = size,
                    originalPath = file.absolutePath,
                    trashedPath = trashed.absolutePath
                )
            )

            applyPolicies()
            true
        }

    // ===========================
    // RESTORE
    // ===========================

    suspend fun restore(entry: TrashEntry): Boolean =
        withContext(Dispatchers.IO) {

            if (!entry.isSaf) {
                val src = File(entry.trashedPath!!)
                val dst = File(entry.originalPath!!)
                dst.parentFile?.mkdirs()

                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, true)
                    src.deleteRecursively()
                }

                store.remove(entry.id)
                return@withContext true
            }

            false
        }

    // ===========================
    // LIST
    // ===========================

    suspend fun list(): List<TrashEntry> =
        store.list()

    suspend fun permanentlyDeleteEntry(entry: TrashEntry): Boolean =
        withContext(Dispatchers.IO) {

            if (!entry.isSaf) {
                entry.trashedPath?.let {
                    File(it).deleteRecursively()
                }
            }

            store.remove(entry.id)
            true
        }

    // ===========================
    // EMPTY
    // ===========================

    suspend fun empty(): Boolean =
        withContext(Dispatchers.IO) {

            nativeTrashDir().deleteRecursively()
            store.clear()
            true
        }

    // ===========================
    // POLICIES
    // ===========================

    private suspend fun applyPolicies() {
        removeExpired()
        enforceQuota()
    }

    private suspend fun removeExpired() {
        val now = System.currentTimeMillis()
        val entries = store.list()

        for (entry in entries) {
            if (now - entry.trashedAt > maxAgeMillis) {
                permanentlyDelete(entry)
            }
        }
    }

    private suspend fun enforceQuota() {
        val entries = store.list()
            .sortedBy { it.trashedAt }

        var totalSize = entries.sumOf { it.size ?: 0 }

        for (entry in entries) {
            if (totalSize <= maxSizeBytes) break

            permanentlyDelete(entry)
            totalSize -= entry.size ?: 0
        }
    }

    private suspend fun permanentlyDelete(entry: TrashEntry) {
        if (!entry.isSaf) {
            entry.trashedPath?.let { File(it).deleteRecursively() }
        }
        store.remove(entry.id)
    }
}
