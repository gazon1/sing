package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import kotlinx.coroutines.flow.Flow

interface SavedAgendaViewsRepository {
    /** Emits all saved views for the given user, ordered by name. */
    fun watchAll(userId: String): Flow<List<SavedAgendaView>>

    /** Emits a single saved view by id, or null if not found. */
    fun watchById(id: SavedAgendaViewId, userId: String): Flow<SavedAgendaView?>

    /** Creates or updates a saved view. Returns the saved view on success. */
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>

    /** Deletes a saved view. Idempotent — succeeds even if the view doesn't exist. */
    suspend fun delete(id: SavedAgendaViewId, userId: String): Result<Unit>
}
