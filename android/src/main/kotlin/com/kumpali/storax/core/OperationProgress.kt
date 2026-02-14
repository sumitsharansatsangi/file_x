package com.kumpali.storax.core

data class OperationProgress(
    val source: String,
    val destination: String,
    val bytesCopied: Long,
    val totalBytes: Long
) {
    val percent: Int
        get() = if (totalBytes == 0L) 0
        else ((bytesCopied * 100) / totalBytes).toInt()
}
