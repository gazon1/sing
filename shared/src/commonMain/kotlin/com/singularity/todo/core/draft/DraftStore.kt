package com.singularity.todo.core.draft

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Port for persisting form drafts across process death.
 *
 * Drafts are stored as JSON-encoded strings in [androidx.datastore.core.DataStore]
 * (Preferences flavor). Drafts are NOT secrets — they use regular DataStore,
 * not [SecureStoragePort].
 *
 * Key format: per-user prefixed, e.g. "user123:task_create_draft".
 * Profile isolation is achieved by including the user ID in the key.
 *
 * Usage:
 * ```
 * // Save
 * draftStore.save("user123:task_create_draft", myDraft, MyDraft.serializer())
 *
 * // Load
 * val draft: MyDraft? = draftStore.load("user123:task_create_draft", MyDraft.serializer())
 *
 * // Clear
 * draftStore.clear("user123:task_create_draft")
 * ```
 */
interface DraftStore {

    /**
     * Loads a draft by key. Returns null if not found or corrupted.
     */
    suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T?

    /**
     * Saves a draft under the given key. Overwrites any existing draft.
     */
    suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>)

    /**
     * Deletes a draft by key. Idempotent — deleting non-existent draft is a no-op.
     */
    suspend fun clear(key: String)
}
