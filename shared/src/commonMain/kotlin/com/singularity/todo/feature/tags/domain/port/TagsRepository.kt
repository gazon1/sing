package com.singularity.todo.feature.tags

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import kotlinx.coroutines.flow.Flow

/**
 * Contract for tags persistence.
 */
interface TagsRepository :
    GenericUserScopedRepository<Tag, TagId>,
    SoftDeletable<Tag, TagId> {

    /**
     * Upserts a tag from a remote sync event.
     * Does NOT emit repository-level change events — caller handles observability.
     * Used by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     */
    suspend fun upsert(tag: Tag): Tag

    /**
     * Deletes every tag of the active profile.
     *
     * One bulk delete per profile, not a sweep of the live rows: a trashed tag is a
     * row the next run still sees, and a sweep is defined by the query that feeds it.
     *
     * @return how many rows were deleted.
     */
    suspend fun deleteAllForCurrentProfile(): Result<Int>

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** Single tag observation by id (no user-filter, uses ambient current user). */
    fun observeTag(id: TagId): Flow<Tag?>
}
