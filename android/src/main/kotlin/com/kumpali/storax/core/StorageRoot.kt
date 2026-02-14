package com.kumpali.storax.core

data class StorageRoot(
    val type: String,          // "native" or "saf"
    val name: String,
    val path: String?,         // native
    val uri: String?,          // saf
    val totalBytes: Long?,
    val freeBytes: Long?,
    val usedBytes: Long?,
    val writable: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "type" to type,
        "name" to name,
        "path" to path,
        "uri" to uri,
        "total" to totalBytes,
        "free" to freeBytes,
        "used" to usedBytes,
        "writable" to writable
    )
}

