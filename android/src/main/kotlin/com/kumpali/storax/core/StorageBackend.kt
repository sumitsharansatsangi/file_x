package com.kumpali.storax.core

interface StorageBackend {

    val type: String

    suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): NodeResult


    suspend fun delete(path: String): Boolean

    suspend fun rename(
        source: String,
        newName: String,
        conflictPolicy: ConflictPolicy,
        manualRename: String?
    ): Boolean
}
