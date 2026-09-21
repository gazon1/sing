package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import kotlinx.coroutines.flow.Flow

interface SavedAgendaViewsRepository {
    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────
    fun watchAllForCurrentUser(): Flow<List<SavedAgendaView>>
    fun watchByIdForCurrentUser(id: SavedAgendaViewId): Flow<SavedAgendaView?>

    /** Creates or updates a saved view. Returns the saved view on success. */
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>

    /** Deletes a saved view. Idempotent — succeeds even if the view doesn't exist. */
    suspend fun delete(id: SavedAgendaViewId): Result<Unit>
}
