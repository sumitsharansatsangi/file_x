package com.kumpali.storax.core

sealed class UndoAction {

    data class Create(
        val path: String,
        val type: NodeType
    ) : UndoAction()

    data class Delete(
        val originalPath: String,
        val backupPath: String
    ) : UndoAction()

    data class Rename(
        val from: String,
        val to: String
    ) : UndoAction()

    data class Move(
        val from: String,
        val to: String
    ) : UndoAction()

    data class Copy(
        val path: String
    ) : UndoAction()
}
