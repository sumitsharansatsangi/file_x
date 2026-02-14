package com.kumpali.storax.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrashStore(context: Context) {

    private val file = File(context.filesDir, "trash_index.json")
    private val mutex = Mutex()

    suspend fun add(entry: TrashEntry) = mutex.withLock {
        val list = readInternal().toMutableList()
        list.add(entry)
        writeInternal(list)
    }

    suspend fun remove(id: String) = mutex.withLock {
        val list = readInternal().filterNot { it.id == id }
        writeInternal(list)
    }

    suspend fun list(): List<TrashEntry> =
        mutex.withLock { readInternal() }

    suspend fun clear() =
        mutex.withLock { file.delete() }

    private fun readInternal(): List<TrashEntry> {
        if (!file.exists()) return emptyList()

        val arr = JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull {
            try {
                TrashEntry.fromMap(
                    arr.getJSONObject(it).toMap()
                )
            } catch (_: Exception) { null }
        }
    }

    private fun writeInternal(list: List<TrashEntry>) {
        val arr = JSONArray(list.map { JSONObject(it.toMap()) })
        file.writeText(arr.toString())
    }

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { opt(it) }
}
