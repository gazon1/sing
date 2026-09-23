package com.singularity.todo.feature.search.domain.port

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId

/**
 * Repository for [SavedSearch] persistence.
 *
 * Implements [GenericUserScopedRepository] for standard CRUD + observation.
 * Additionally supports [findByNameForUser] for duplicate-name detection.
 */
interface SavedSearchRepository : GenericUserScopedRepository<SavedSearch, SavedSearchId> {

    /**
     * Returns the ambient userId string for this repository's scope.
     */
    suspend fun currentUserId(): String

    /**
     * Finds a saved search by name for the given user, or `null` if none exists.
     * Used for duplicate-name validation.
     */
    suspend fun findByNameForUser(userId: String, name: String): SavedSearch?

    /**
     * Creates or updates a saved search. Returns the saved search on success.
     */
    suspend fun upsert(search: SavedSearch): Result<SavedSearch>

    /**
     * Canonical CRUD — delegates to [upsert].
     */
    override suspend fun create(item: SavedSearch): Result<SavedSearch> = upsert(item)

    override suspend fun update(item: SavedSearch): Result<SavedSearch> = upsert(item)
}
