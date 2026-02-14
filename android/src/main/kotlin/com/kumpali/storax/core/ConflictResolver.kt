package com.kumpali.storax.core

object ConflictResolver {

    fun resolve(
        exists: (String) -> Boolean,
        baseName: String,
        policy: ConflictPolicy,
        manualRename: String?
    ): String? {

        if (!exists(baseName)) return baseName

        return when (policy) {

            ConflictPolicy.FAIL -> null

            ConflictPolicy.REPLACE -> baseName

            ConflictPolicy.RENAME_NEW ->
                generateIncrementedName(exists, baseName)

            ConflictPolicy.RENAME_MANUAL ->
                manualRename?.takeIf { it.isNotBlank() }
        }
    }

    private fun generateIncrementedName(
        exists: (String) -> Boolean,
        baseName: String
    ): String {

        var counter = 1
        var candidate: String

        do {
            candidate = "$baseName ($counter)"
            counter++
        } while (exists(candidate))

        return candidate
    }
}
