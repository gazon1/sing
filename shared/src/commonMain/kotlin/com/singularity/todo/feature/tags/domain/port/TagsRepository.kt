package com.singularity.todo.feature.tags

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.core.search.TextMatchQuery
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

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** Single tag observation by id (no user-filter, uses ambient current user). */
    fun observeTag(id: TagId): Flow<Tag?>

    /**
     * Observes the tags whose name matches [query].
     *
     * The lookup goes through the full-text index, so the cost of the search
     * does not grow with the number of tags. The match is a *word-prefix*
     * match — the semantics of the index — and the terms are combined the way
     * [query] says: every term, or any of them.
     */
    fun observeByTextMatch(query: TextMatchQuery): Flow<List<Tag>>
}
