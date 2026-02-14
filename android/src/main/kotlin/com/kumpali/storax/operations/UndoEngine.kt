package com.kumpali.storax.operations

import android.content.Context
import com.kumpali.storax.core.NodeType
import com.kumpali.storax.core.UndoAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque

class UndoEngine(
    context: Context,
    private val maxSize: Int = 100
) {

    private val mutex = Mutex()

    private val rootDir = File(context.filesDir, "storax_undo")
    private val undoFile = File(rootDir, "undo_stack.json")
    private val redoFile = File(rootDir, "redo_stack.json")

    private val undoStack = ArrayDeque<UndoAction>()
    private val redoStack = ArrayDeque<UndoAction>()

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            fsyncDirectory(rootDir.parentFile)
        }
        loadFromDisk()
    }

    // ============================================================
    // ======================== PUBLIC API ========================
    // ============================================================

    suspend fun register(action: UndoAction) {
        mutex.withLock {

            if (undoStack.size >= maxSize) {
                undoStack.removeFirst()
            }

            undoStack.addLast(action)
            redoStack.clear()

            persist()
        }
    }

    suspend fun undoLast(
        executor: suspend (UndoAction) -> Boolean
    ): Boolean {

        val action = mutex.withLock {
            undoStack.lastOrNull()
        } ?: return false

        val success = executor(action)
        if (!success) return false

        mutex.withLock {
            undoStack.removeLast()
            redoStack.addLast(action)
            persist()
        }

        return true
    }

    suspend fun redoLast(
        executor: suspend (UndoAction) -> Boolean
    ): Boolean {

        val action = mutex.withLock {
            redoStack.lastOrNull()
        } ?: return false

        val success = executor(action)
        if (!success) return false

        mutex.withLock {
            redoStack.removeLast()
            undoStack.addLast(action)
            persist()
        }

        return true
    }

    suspend fun clear() {
        mutex.withLock {
            undoStack.clear()
            redoStack.clear()
            persist()
        }
    }

    suspend fun canUndo() = mutex.withLock { undoStack.isNotEmpty() }
    suspend fun canRedo() = mutex.withLock { redoStack.isNotEmpty() }
    suspend fun undoCount() = mutex.withLock { undoStack.size }
    suspend fun redoCount() = mutex.withLock { redoStack.size }

    // ============================================================
    // ======================== PERSISTENCE ========================
    // ============================================================

    private fun persist() {
        writeAtomically(undoFile, stackToJson(undoStack))
        writeAtomically(redoFile, stackToJson(redoStack))
    }

    private fun loadFromDisk() {
        undoStack.clear()
        redoStack.clear()

        undoStack.addAll(readStack(undoFile))
        redoStack.addAll(readStack(redoFile))
    }

    private fun stackToJson(stack: ArrayDeque<UndoAction>): String {
        val array = JSONArray()

        stack.forEach { action ->
            array.put(actionToJson(action))
        }

        return array.toString()
    }

    private fun readStack(file: File): List<UndoAction> {
        if (!file.exists()) return emptyList()

        return try {
            val text = file.readText(Charsets.UTF_8)
            val array = JSONArray(text)
            val list = mutableListOf<UndoAction>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                jsonToAction(obj)?.let { list.add(it) }
            }

            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================================================
    // ===================== SERIALIZATION ========================
    // ============================================================

    private fun actionToJson(action: UndoAction): JSONObject {
        return when (action) {

            is UndoAction.Create -> JSONObject().apply {
                put("type", "CREATE")
                put("path", action.path)
                put("nodeType", action.type.name)
            }

            is UndoAction.Delete -> JSONObject().apply {
                put("type", "DELETE")
                put("originalPath", action.originalPath)
                put("backupPath", action.backupPath)
            }

            is UndoAction.Rename -> JSONObject().apply {
                put("type", "RENAME")
                put("from", action.from)
                put("to", action.to)
            }

            is UndoAction.Move -> JSONObject().apply {
                put("type", "MOVE")
                put("from", action.from)
                put("to", action.to)
            }

            is UndoAction.Copy -> JSONObject().apply {
                put("type", "COPY")
                put("path", action.path)
            }
        }
    }

    private fun jsonToAction(obj: JSONObject): UndoAction? {

        return when (obj.optString("type")) {

            "CREATE" -> {

                val path = obj.optString("path")
                val typeName = obj.optString("nodeType")

                if (path.isEmpty() || typeName.isEmpty()) {
                    null
                } else {
                    val nodeType = try {
                        NodeType.valueOf(typeName)
                    } catch (_: Exception) {
                        return null
                    }

                    UndoAction.Create(path, nodeType)
                }
            }


            "DELETE" -> {
                val original = obj.optString("originalPath")
                val backup = obj.optString("backupPath")
                if (original.isEmpty() || backup.isEmpty()) null
                else UndoAction.Delete(original, backup)
            }

            "RENAME" -> {
                val from = obj.optString("from")
                val to = obj.optString("to")
                if (from.isEmpty() || to.isEmpty()) null
                else UndoAction.Rename(from, to)
            }

            "MOVE" -> {
                val from = obj.optString("from")
                val to = obj.optString("to")
                if (from.isEmpty() || to.isEmpty()) null
                else UndoAction.Move(from, to)
            }

            "COPY" -> {
                val path = obj.optString("path")
                if (path.isEmpty()) null
                else UndoAction.Copy(path)
            }

            else -> null
        }
    }

    // ============================================================
    // ======================= ATOMIC WRITE =======================
    // ============================================================

    private fun writeAtomically(file: File, data: String) {

        val temp = File(file.parentFile, file.name + ".tmp")

        FileOutputStream(temp).use { os ->
            os.write(data.toByteArray(Charsets.UTF_8))
            os.flush()
            os.fd.sync()
        }

        if (!temp.renameTo(file)) {
            temp.delete()
            throw IllegalStateException("Undo atomic rename failed")
        }

        fsyncDirectory(file.parentFile)
    }

    private fun fsyncDirectory(dir: File?) {
        if (dir == null) return
        try {
            RandomAccessFile(dir, "r").use {
                it.fd.sync()
            }
        } catch (_: Exception) {
            // best effort
        }
    }
}
