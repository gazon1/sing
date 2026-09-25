package com.singularity.todo.core.repository

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser

/**
 * Thrown when a repository write targets a different user than the currently
 * scoped profile.
 */
class CrossUserWriteException(message: String) : IllegalStateException(message)

/**
 * Guards every repository write. Must be called as the first statement of
 * `create` / `update` before any Room write.
 *
 * Stamping `UserId.anonymous` entities with the current scoped user id is
 * the caller's responsibility — each entity has its own `copy` semantics.
 *
 * @param entityId Identifier used only in the error message when the guard
 *                 fails. Pass [com.singularity.todo.core.sync.SyncableEntity.syncId]
 *                 when available, or the entity's own id otherwise.
 * @param entityUserId The userId embedded in the entity being written.
 * @throws CrossUserWriteException when [entityUserId] is neither the current
 *         scoped user nor [UserId.anonymous].
 */
fun ProfileAwareCurrentUser.assertCanWrite(entityId: String, entityUserId: UserId) {
    val currentUid = scopedUserId.value
    if (entityUserId != currentUid && entityUserId != UserId.anonymous) {
        throw CrossUserWriteException(
            "Cross-user write attempted for $entityId: " +
                "entity.userId=$entityUserId, current=$currentUid",
        )
    }
}
