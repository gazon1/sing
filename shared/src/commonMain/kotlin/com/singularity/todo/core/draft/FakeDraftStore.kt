package com.singularity.todo.core.draft

import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * In-memory [DraftStore] fake for tests and previews.
 *
 * Stores drafts as JSON strings in a [MutableMap], mimicking the real
 * [DataStoreDraftStore] pipeline (JSON encode → save → JSON decode → load).
 * This catches regressions in JSON schema changes during tests.
 */
class FakeDraftStore : DraftStore {

    private val map = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? = mutex.withLock {
        map[key]?.let { json ->
            runCatchingCancellable { StableJson.decodeFromString(deserializer, json) }.getOrNull()
        }
    }

    override suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>) = mutex.withLock {
        map[key] = StableJson.encodeToString(serializer, value)
    }

    override suspend fun clear(key: String) {
        mutex.withLock { map.remove(key) }
    }
}
