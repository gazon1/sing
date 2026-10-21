package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView

/**
 * Repository for user-scoped [SavedAgendaView] persistence.
 *
 * Extends [GenericUserScopedRepository] for standard CRUD + [observeAll] watching.
 * Provides [upsert] as the single write primitive, with [duplicateForProfile] for
 * cross-profile view copying.
 */
interface SavedAgendaViewsRepository : GenericUserScopedRepository<SavedAgendaView, SavedAgendaViewId> {

    /** Returns the ambient userId string for this repository's scope. */
    suspend fun currentUserId(): String

    /** Creates or updates a saved view. Returns the saved view on success. */
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>

    /**
     * Hard-deletes every saved view the active profile owns and reports how many
     * rows went.
     *
     * ## Why this exists
     *
     * A saved view is profile content like any other: one left behind by an
     * earlier run is a view the next run sees, and it changes what the agenda
     * shows. This is the one operation that reaches the views of the profile as
     * a whole.
     *
     * ## What it does not do
     *
     * It touches nothing outside the active profile: the scope is the current
     * profile's scoped user id, which is what every row of that profile
     * carries.
     *
     * @return the number of rows removed.
     */
    suspend fun deleteAllForCurrentProfile(): Result<Int>

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
