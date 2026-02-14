package com.kumpali.storax.core

data class TrashEntry(
    val id: String,
    val name: String,
    val isSaf: Boolean,
    val isDirectory: Boolean,
    val trashedAt: Long,
    val size: Long? = null,
    val originalPath: String? = null,
    val trashedPath: String? = null,

    val originalUri: String? = null,
    val trashedUri: String? = null,
    val safRootUri: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "isSaf" to isSaf,
        "size" to size,
        "isDirectory" to isDirectory,
        "trashedAt" to trashedAt,
        "originalPath" to originalPath,
        "trashedPath" to trashedPath,
        "originalUri" to originalUri,
        "trashedUri" to trashedUri,
        "safRootUri" to safRootUri
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): TrashEntry =
            TrashEntry(
                id = map["id"] as String,
                name = map["name"] as String,
                isSaf = map["isSaf"] as Boolean,
                size = (map["size"] as Number).toLong(),
                isDirectory = map["isDirectory"] as Boolean,
                trashedAt = (map["trashedAt"] as Number).toLong(),
                originalPath = map["originalPath"] as String?,
                trashedPath = map["trashedPath"] as String?,
                originalUri = map["originalUri"] as String?,
                trashedUri = map["trashedUri"] as String?,
                safRootUri = map["safRootUri"] as String?
            )
    }
}
