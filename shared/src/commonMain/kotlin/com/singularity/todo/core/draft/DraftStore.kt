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

    /**
     * Deletes every draft whose key starts with [prefix].
     *
     * A prefix of `""` deletes every draft the store holds.
     *
     * This exists because a store that can only delete a *named* draft cannot
     * be emptied, and a store that cannot be emptied accumulates: a form that
     * restores its draft hands the next session the previous session's text.
     * The caller that owns the namespace knows the prefix; the store knows
     * only the keys, so the prefix is passed in rather than guessed here.
     */
    suspend fun clearPrefix(prefix: String)
}
