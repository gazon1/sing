package com.singularity.todo.core.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory fake of [SecureStoragePort] for unit tests.
 * Thread-safe via a [Mutex].
 */
class FakeSecureStorage(
    private val backing: MutableMap<String, String> = mutableMapOf(),
    private val hardwareBacked: Boolean = true
) : SecureStoragePort {

    private val mutex = Mutex()

    override suspend fun read(key: String): String? = mutex.withLock { backing[key] }

    override suspend fun write(key: String, value: String) = mutex.withLock {
        backing[key] = value
    }

    override suspend fun delete(key: String) = mutex.withLock {
        backing.remove(key)
        Unit
    }

    override fun isHardwareBacked(): Boolean = hardwareBacked
}
