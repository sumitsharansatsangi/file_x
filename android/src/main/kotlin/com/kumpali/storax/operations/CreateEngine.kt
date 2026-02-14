package com.kumpali.storax.operations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kumpali.storax.core.*

class CreateNodeEngine(
    private val context: Context,
    private val lockManager: LockManager
) {

    suspend fun create(
        parent: String,
        name: String,
        type: NodeType,
        conflictPolicy: ConflictPolicy,
        manualRename: String? = null
    ): NodeResult = withContext(Dispatchers.IO) {

        require(parent.isNotBlank()) { "Parent cannot be empty" }
        require(name.isNotBlank()) { "Name cannot be empty" }

        val backend = BackendDetector.detect(context, parent)

        val lockKey = "create::$parent/$name"

        val result = lockManager.withLock(lockKey) {
            backend.createNode(
                parent = parent,
                name = name,
                type = type,
                conflictPolicy = conflictPolicy,
                manualRename = manualRename
            )
        } ?: return@withContext NodeResult(
            success = false,
            error = "Operation timeout"
        )

        result
    }
}
