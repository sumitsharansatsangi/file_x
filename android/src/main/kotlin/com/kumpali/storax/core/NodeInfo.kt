package com.kumpali.storax.core

data class NodeInfo(
    val name: String,
    val pathOrUri: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
