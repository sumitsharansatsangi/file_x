package com.kumpali.storax.core

enum class NodeType(val code: Int) {
    FILE(0),
    DIRECTORY(1);

    companion object {
        private val byCode = NodeType.entries.associateBy { it.code }

        fun fromCode(code: Int): NodeType =
            byCode[code] ?: FILE
    }
}
