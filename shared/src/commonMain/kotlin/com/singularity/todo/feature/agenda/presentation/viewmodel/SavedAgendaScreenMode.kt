package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition

/**
 * Runtime mode for [SavedAgendaViewModel].
 *
 * Determines whether the VM is editing, creating, or displaying results for
 * a saved view.
 */
sealed interface SavedAgendaScreenMode {
    /** Display the tasks matching a saved view's definition. */
    data class View(val viewId: SavedAgendaViewId) : SavedAgendaScreenMode

    /** Edit an existing saved view by ID. */
    data class Edit(val viewId: SavedAgendaViewId) : SavedAgendaScreenMode

    /** Create a new view, seeded from [seed]. */
    data class Create(val seed: AgendaDefinition) : SavedAgendaScreenMode
}
