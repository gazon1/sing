package com.singularity.todo.core.draft

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * A [DraftStore] wrapper that prepends `"${userId}:"` to every key internally,
 * so callers work with bare keys while user isolation is handled automatically.
 *
 * This replaces the ad-hoc `"${currentUser.scopedUserId.value.value}:$DRAFT_KEY"`
 * string拼接 pattern that previously lived in [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel].
 *
 * Usage:
 * ```
 * // Save — caller passes bare key, wrapper prepends the user prefix
 * userScopedDraftStore.save("task_create_draft", myDraft, MyDraft.serializer())
 *
 * // Load — returns null if not found or corrupted
 * val draft: MyDraft? = userScopedDraftStore.load("task_create_draft", MyDraft.serializer())
 *
 * // Clear
 * userScopedDraftStore.clear("task_create_draft")
 * ```
 *
 * @param currentUser Source of the ambient user ID used as the key prefix.
 * @param delegate The underlying [DraftStore] that performs the actual JSON persistence.
 */
class UserScopedDraftStore(
    private val currentUser: ProfileAwareCurrentUser,
    private val delegate: DraftStore,
) : DraftStore {

    private val userIdPrefix: String
        get() = "${currentUser.scopedUserId.value.value}:"

    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? {
        return delegate.load(userIdPrefix + key, deserializer)
    }

    override suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>) {
        delegate.save(userIdPrefix + key, value, serializer)
    }

    override suspend fun clear(key: String) {
        delegate.clear(userIdPrefix + key)
    }
}
