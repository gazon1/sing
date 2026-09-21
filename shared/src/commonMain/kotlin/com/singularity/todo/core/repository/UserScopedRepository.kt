package com.singularity.todo.core.repository

import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for repositories that own per-user data isolation.
 *
 * ## Purpose
 *
 * Declares the minimal contract that all user-scoped repositories implement.
 * Concrete repositories add domain-specific observation methods on top of this
 * (e.g., `observeByFilter`, `observeByDate`) following the naming convention:
 * base interface methods use the `ForCurrentUser` suffix; domain-specific
 * observers use short form without the suffix.
 *
 * ## Design: composition over inheritance
 *
 * There is no abstract base class or shared state. Each repository owns its
 * own data source and `ProfileAwareCurrentUser` instance internally.
 * See [InMemoryStore.kt:13][com.singularity.todo.test.fakes.InMemoryStore] for the
 * design rationale ("Avoid simple boundary classes").
 *
 * ## Contract
 *
 * - **Observations** are scoped to `ProfileAwareCurrentUser.scopedUserId`.
 *   They automatically re-subscribe when the active user changes.
 * - **Mutations** use `ProfileAwareCurrentUser.current` (imperative read) to
 *   stamp `entity.userId` on create. Callers are responsible for providing
 *   correct `userId` in update/delete operations (caller-trust model).
 *
 * @param T The entity type (e.g., [Task], [Note], [Project]).
 * @param ID The entity's ID type (e.g., [TaskId], [NoteId], [ProjectId]).
 */
interface UserScopedRepository<T, ID> {

    /**
     * Observes all entities for the currently authenticated user.
     * Re-subscribes automatically when the user changes.
     */
    fun observeAllForCurrentUser(): Flow<List<T>>

    /**
     * Observes a single entity by [id] for the currently authenticated user.
     * Emits `null` if the entity does not exist or belongs to another user.
     */
    fun observeForCurrentUser(id: ID): Flow<T?>

    /**
     * Creates a new entity. The entity should already have `userId` set
     * (typically via [ProfileAwareCurrentUser.current] in the caller).
     */
    suspend fun create(item: T): Result<Unit>

    /**
     * Updates an existing entity. The caller is responsible for ensuring
     * the entity belongs to the current user (caller-trust).
     */
    suspend fun update(item: T): Result<Unit>

    /**
     * Deletes an entity by [id]. The caller is responsible for ensuring
     * the entity belongs to the current user (caller-trust).
     */
    suspend fun delete(id: ID): Result<Unit>
}
