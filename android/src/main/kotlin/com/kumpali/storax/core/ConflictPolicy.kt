package com.kumpali.storax.core

enum class ConflictPolicy(val code: Int) {
    FAIL(0),
    REPLACE(1),
    RENAME_NEW(2),
    RENAME_MANUAL(3);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): ConflictPolicy =
            byCode[code] ?: FAIL
    }
}
