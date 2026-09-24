package com.singularity.todo.core.repository

import kotlinx.coroutines.flow.Flow

/**
 * Generic CRUD base for user-scoped entities. The current user is resolved from
 * ambient [ProfileAwareCurrentUser][com.singularity.todo.feature.profile.ProfileAwareCurrentUser];
 * do NOT pass userId explicitly.
 *
 * ## Contract
 *
 * - **Observations** are scoped to `ProfileAwareCurrentUser.scopedUserId`.
 *   They automatically re-subscribe when the active user changes.
 * - **Mutations** use `ProfileAwareCurrentUser.current` (imperative read) to
 *   stamp `entity.userId` on create. Callers are responsible for providing
 *   correct `userId` in update/delete operations (caller-trust model).
 * - **create / update** return `Result<E>` — the saved entity with assigned id
 *   and timestamps. Spring Data / Django ORM convention.
 *
 * ## Design: composition over inheritance
 *
 * Each repository owns its own data source and `ProfileAwareCurrentUser` instance
 * internally. Domain-specific operations (search, archive, parent-child links,
 * sync fan-out) belong on entity-specific interfaces that extend this base.
 *
 * ## Extension helpers
 *
 * [exists][exists] is provided as an extension. Domain-specific helpers that
 * appear in two or more repositories should be promoted from extension to
 * default interface method.
 *
 * @param E The entity type (e.g., [Task][com.singularity.todo.feature.tasks.domain.model.Task],
 *           [Note][com.singularity.todo.feature.notes.domain.model.Note],
 *           [Project][com.singularity.todo.feature.projects.domain.model.Project]).
 * @param ID The entity's ID type (e.g., [TaskId][com.singularity.todo.core.ids.TaskId],
 *           [NoteId][com.singularity.todo.core.ids.NoteId],
 *           [ProjectId][com.singularity.todo.core.ids.ProjectId]).
 */
interface GenericUserScopedRepository<E, ID> {

    /**
     * Observes all entities for the currently authenticated user.
     * Re-subscribes automatically when the user changes.
     */
    fun observeAll(): Flow<List<E>>

    /**
     * Observes a single entity by [id] for the currently authenticated user.
     *
     * - **null emission** = entity was soft/hard deleted
     * - **no emission** = entity never existed (cold start before first observation)
     *
     * Use [exists] to distinguish "never existed" from "was deleted".
     */
    fun observe(id: ID): Flow<E?>

    /**
     * Retrieves a single entity by [id] for the current user.
     * Returns `null` if not found or the entity belongs to another user.
     */
    suspend fun get(id: ID): E?

    /**
     * Creates a new entity. The entity should already have `userId` set
     * (typically via [ProfileAwareCurrentUser.current]
     * [com.singularity.todo.feature.profile.ProfileAwareCurrentUser.current] in the caller).
     * Returns the saved entity with assigned id and timestamps.
     */
    suspend fun create(item: E): Result<E>

    /**
     * Updates an existing entity. The caller is responsible for ensuring
     * the entity belongs to the current user (caller-trust).
     * Returns the updated entity.
     */
    suspend fun update(item: E): Result<E>

    /**
     * Deletes an entity by [id]. The caller is responsible for ensuring
     * the entity belongs to the current user (caller-trust).
     * Soft-delete is implied when the entity supports [SoftDeletable]; hard-delete
     * when it does not.
     */
    suspend fun delete(id: ID): Result<Unit>
}

/**
 * Soft-delete reversal. Implementations decide whether [delete][GenericUserScopedRepository.delete]
 * is hard or soft; [restore] undoes a soft-delete.
 *
 * Used by [Task][com.singularity.todo.feature.tasks.domain.model.Task],
 * [Project][com.singularity.todo.feature.projects.domain.model.Project], and
 * [Note][com.singularity.todo.feature.notes.domain.model.Note].
 */
interface SoftDeletable<E, ID> {

    /**
     * Restores a soft-deleted entity by [id].
     * No-op if the entity does not support soft-delete or is not deleted.
     */
    suspend fun restore(id: ID): Result<Unit>
}

/**
 * Returns `true` if an entity with the given [id] exists for the current user.
 */
suspend fun <E, ID> GenericUserScopedRepository<E, ID>.exists(id: ID): Boolean = get(id) != null
