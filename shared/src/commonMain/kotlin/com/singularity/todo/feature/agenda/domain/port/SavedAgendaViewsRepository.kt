package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import kotlinx.coroutines.flow.Flow

interface SavedAgendaViewsRepository : GenericUserScopedRepository<SavedAgendaView, SavedAgendaViewId> {

    /** Creates or updates a saved view. Returns the saved view on success. */
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>

    /** Deletes a saved view. Idempotent — succeeds even if the view doesn't exist. */
    override suspend fun delete(id: SavedAgendaViewId): Result<Unit>

    /** Canonical CRUD — delegates to [upsert]. */
    override suspend fun create(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun update(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)
}
