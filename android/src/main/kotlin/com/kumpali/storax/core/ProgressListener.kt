package com.kumpali.storax.core

fun interface ProgressListener {
    suspend fun onProgress(progress: OperationProgress)
}
