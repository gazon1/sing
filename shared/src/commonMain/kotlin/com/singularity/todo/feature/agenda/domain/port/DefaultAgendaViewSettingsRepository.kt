package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlinx.coroutines.flow.Flow

/**
 * Contract for the default agenda view setting.
 */
interface DefaultAgendaViewSettingsRepository {

    val defaultViewId: Flow<SavedAgendaViewId?>
    suspend fun setDefaultViewId(id: SavedAgendaViewId?)
}
