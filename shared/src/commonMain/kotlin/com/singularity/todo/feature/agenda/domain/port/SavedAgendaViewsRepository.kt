package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView

/**
 * Repository for saved agenda view configurations per profile.
 * Extends GenericUserScopedRepository.
 */
interface SavedAgendaViewsRepository : GenericUserScopedRepository<SavedAgendaView, SavedAgendaViewId> {

    /** Returns the ambient userId string for this repository's scope. */
    suspend fun currentUserId(): String

    /** Creates or updates a saved view. Returns the saved view on success. */
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>

    /**
     * Duplicates a [SavedAgendaView] into a different user profile.
     * Generates a fresh [SavedAgendaView.id], stamps [targetUserId], resets timestamps.
     */
    suspend fun duplicateForProfile(view: SavedAgendaView, targetUserId: String): Result<SavedAgendaView>

    /** Deletes a saved view. Idempotent — succeeds even if the view doesn't exist. */
    override suspend fun delete(id: SavedAgendaViewId): Result<Unit>

    /** Canonical CRUD — delegates to [upsert]. */
    override suspend fun create(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun update(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)
}
