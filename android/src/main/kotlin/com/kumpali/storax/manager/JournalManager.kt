package com.kumpali.storax.manager

import android.content.Context
import com.kumpali.storax.core.ConflictPolicy
import com.kumpali.storax.core.NodeResult
import com.kumpali.storax.core.NodeType
import com.kumpali.storax.core.StorageBackend
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class JournalManager(private val context: Context) {

    private val journalDir = File(context.filesDir, "storax_journal")

    init {
        if (!journalDir.exists()) {
            journalDir.mkdirs()
            fsyncDirectory(journalDir.parentFile)
        }
    }

    private fun uniqueName(prefix: String): String =
        "${prefix}_${System.nanoTime()}_${Thread.currentThread().id}.json"

    // ============================================================
    // ======================= BEGIN OPERATIONS ===================
    // ============================================================

    fun beginRename(source: String, targetName: String): File {

        val final = File(journalDir, uniqueName("rename"))
        val temp = File(journalDir, final.name + ".tmp")

        val json = JSONObject().apply {
            put("type", "rename")
            put("source", source)
            put("targetName", targetName)
            put("completed", false)
        }

        writeAtomically(temp, final, json.toString())
        return final
    }

    fun beginCreate(
        parent: String,
        name: String,
        nodeType: NodeType,
        conflictPolicy: ConflictPolicy
    ): File {

        val final = File(journalDir, uniqueName("create"))
        val temp = File(journalDir, final.name + ".tmp")

        val json = JSONObject().apply {
            put("type", "create")
            put("parent", parent)
            put("name", name)
            put("nodeType", nodeType.name)
            put("conflictPolicy", conflictPolicy.name)
            put("completed", false)
        }

        writeAtomically(temp, final, json.toString())
        return final
    }

    fun markCompleted(file: File) {

        val content = safeReadJson(file) ?: return
        content.put("completed", true)

        val temp = File(file.parentFile, file.name + ".tmp")
        writeAtomically(temp, file, content.toString())
    }

    fun remove(file: File) {
        if (file.exists()) {
            file.delete()
            fsyncDirectory(journalDir)
        }
    }

    // ============================================================
    // ======================= ATOMIC WRITE ========================
    // ============================================================

    private fun writeAtomically(temp: File, final: File, data: String) {

        FileOutputStream(temp).use { os ->
            os.write(data.toByteArray(Charsets.UTF_8))
            os.flush()
            os.fd.sync()
        }

        if (!temp.renameTo(final)) {
            temp.delete()
            throw IllegalStateException("Atomic rename failed")
        }

        fsyncDirectory(journalDir)
    }

    private fun fsyncDirectory(dir: File?) {
        if (dir == null) return
        try {
            RandomAccessFile(dir, "r").use {
                it.fd.sync()
            }
        } catch (_: Exception) {
            // Best effort
        }
    }

    // ============================================================
    // ========================= RECOVERY ==========================
    // ============================================================

    suspend fun recoverPendingOperations(
        backendResolver: (String) -> StorageBackend?
    ) {

        journalDir.listFiles()?.forEach { journal ->

            val json = safeReadJson(journal) ?: run {
                journal.delete()
                fsyncDirectory(journalDir)
                return@forEach
            }

            if (json.optBoolean("completed", false)) {
                journal.delete()
                fsyncDirectory(journalDir)
                return@forEach
            }

            when (json.optString("type")) {

                // ================= RENAME =================
                "rename" -> {

                    val source = json.optString("source")
                    if (source.isEmpty()) return@forEach

                    val targetName = json.optString("targetName")
                    if (targetName.isEmpty()) return@forEach

                    val backend = backendResolver(source) ?: return@forEach

                    val parent = File(source).parent ?: return@forEach
                    val target = File(parent, targetName)
                    val sourceFile = File(source)

                    when {
                        sourceFile.exists() && !target.exists() -> {
                            val success = backend.rename(
                                source,
                                targetName,
                                ConflictPolicy.REPLACE,
                                null
                            )
                            if (!success) return@forEach
                        }

                        !sourceFile.exists() && target.exists() -> {
                            // Already completed before crash
                        }

                        else -> return@forEach
                    }

                    journal.delete()
                    fsyncDirectory(journalDir)
                }

                // ================= CREATE =================
                "create" -> {

                    val parent = json.optString("parent")
                    if (parent.isEmpty()) return@forEach

                    val name = json.optString("name")
                    if (name.isEmpty()) return@forEach

                    val typeName = json.optString("nodeType")
                    if (typeName.isEmpty()) return@forEach

                    val policyName = json.optString("conflictPolicy")
                    if (policyName.isEmpty()) return@forEach

                    val nodeType = try {
                        NodeType.valueOf(typeName)
                    } catch (_: Exception) {
                        return@forEach
                    }

                    val conflictPolicy = try {
                        ConflictPolicy.valueOf(policyName)
                    } catch (_: Exception) {
                        return@forEach
                    }

                    val backend = backendResolver(parent) ?: return@forEach
                    val createdFile = File(parent, name)

                    if (createdFile.exists()) {
                        // Create already succeeded
                        journal.delete()
                        fsyncDirectory(journalDir)
                        return@forEach
                    }

                    val result: NodeResult = backend.create(
                        parent = parent,
                        name = name,
                        type = nodeType,
                        conflictPolicy = conflictPolicy,
                        manualRename = null
                    )

                    if (!result.success) {
                        return@forEach
                    }

                    journal.delete()
                    fsyncDirectory(journalDir)
                }
            }
        }
    }

    // ============================================================
    // ===================== SAFE JSON READER ======================
    // ============================================================

    private fun safeReadJson(file: File): JSONObject? {
        return try {
            val text = file.readText(Charsets.UTF_8)
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }
}
