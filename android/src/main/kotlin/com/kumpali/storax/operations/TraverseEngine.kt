package com.kumpali.storax.operations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kumpali.storax.core.NodeInfo
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque

class TraverseEngine(
    private val context: Context
) {

    private val listEngine = ListEngine(context)

    /**
     * Traverses directory recursively.
     *
     * @param rootPath Starting directory
     * @param maxDepth -1 for unlimited
     */
    suspend fun traverse(
        rootPath: String,
        maxDepth: Int = -1
    ): List<NodeInfo> = withContext(Dispatchers.IO) {

        val result = mutableListOf<NodeInfo>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()

        queue.add(rootPath to 0)

        while (queue.isNotEmpty()) {

            coroutineContext.ensureActive()

            val (currentPath, depth) = queue.removeFirst()

            if (!visited.add(currentPath)) continue
            if (maxDepth != -1 && depth >= maxDepth) continue

            val children = runCatching {
                listEngine.list(currentPath)
            }.getOrElse { emptyList() }

            for (child in children) {

                result.add(child)

                if (child.isDirectory) {
                    queue.add(child.pathOrUri to depth + 1)
                }
            }
        }

        result
    }

}
