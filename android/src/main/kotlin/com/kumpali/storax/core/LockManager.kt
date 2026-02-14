package com.kumpali.storax.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class LockManager(
    private val timeoutMs: Long = 10_000L
) {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T
    ): T? {

        val mutex = locks.getOrPut(key) { Mutex() }

        val acquired = withTimeoutOrNull(timeoutMs) {
            mutex.lock()
            true
        } ?: false

        if (!acquired) return null

        try {
            return block()
        } finally {
            mutex.unlock()
            if (!mutex.isLocked) {
                locks.remove(key, mutex)
            }
        }
    }
}
