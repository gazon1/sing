package com.singularity.todo.test.fakes

import com.singularity.todo.core.draft.DraftStore
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * A draft store whose writes fail.
 *
 * The editor has to survive it: the autosave is a safety net, and a broken store
 * must not take the editor down with it. The write attempts are counted, because
 * a failure the editor never saw proves nothing.
 */
class FakeFailingDraftStore : DraftStore {
    /** How many writes were attempted — a failure the editor never saw proves nothing. */
    var saveAttempts = 0
        private set

    override suspend fun <T> load(
        key: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = null

    override suspend fun <T> save(
        key: String,
        value: T,
        serializer: SerializationStrategy<T>,
    ) {
        saveAttempts++
        throw IllegalStateException("the store is down")
    }

    override suspend fun clear(key: String) = Unit

    override suspend fun clearPrefix(prefix: String) = Unit
}
