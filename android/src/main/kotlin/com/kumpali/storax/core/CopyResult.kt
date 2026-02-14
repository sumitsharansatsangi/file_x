package com.kumpali.storax.core

import kotlinx.coroutines.flow.Flow

sealed class CopyResult {
    data class Immediate(val success: Boolean) : CopyResult()
    data class Transaction(
        val jobId: String,
        val flow: Flow<OperationProgress>
    ) : CopyResult()
}