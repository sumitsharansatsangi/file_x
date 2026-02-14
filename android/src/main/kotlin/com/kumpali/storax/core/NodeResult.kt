package com.kumpali.storax.core

data class FolderResult(
    val success: Boolean,
    val finalName: String? = null,
    val pathOrUri: String? = null,
    val error: String? = null
)
