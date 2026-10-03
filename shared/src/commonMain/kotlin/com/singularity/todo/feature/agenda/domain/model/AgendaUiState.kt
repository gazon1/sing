package com.singularity.todo.feature.agenda.domain.model
import androidx.compose.runtime.Immutable

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

/**
 * UI state for the Agenda screen.
 *
 * @param sections The evaluated and rendered sections with their tasks.
 * @param today The current date at the time of evaluation, used to label relative buckets.
 */
@Immutable
sealed interface AgendaUiState {
    data object Loading : AgendaUiState
    data class Loaded(val sections: List<RenderedSection>, val today: LocalDate) : AgendaUiState
    data class Error(val message: String) : AgendaUiState
}

/**
 * A section that has been evaluated against a task list and is ready to render.
 *
 * @param id Section identifier, from [Section.id].
 * @param name Section display name (from [Section.name]).
 * @param tasks The tasks matching the section's [Selector], already filtered by
 *        [Section.discard] semantics from previous sections.
 * @param badge Optional count badge shown in the section header (e.g. "12" for overdue).
 * @param prefill Pre-fill data for the '+' create button, from [Section.prefill].
 */
data class RenderedSection(
    val id: String,
    val name: String,
    val tasks: List<AgendaRowItem>,
    val badge: Int? = null,
    val prefill: SectionPrefill? = null,
)

/**
 * A single task row within a rendered [RenderedSection].
 *
 * Thin wrapper around [com.singularity.todo.feature.tasks.domain.model.Task]
 * that carries agenda-specific display metadata.
 *
 * @param task The underlying task.
 * @param badge Optional per-task badge (e.g. "recurring", "overdue").
 */
data class AgendaRowItem(
    val task: com.singularity.todo.feature.tasks.domain.model.Task,
    val badge: AgendaBadge? = null,
    /** MR-1: true when the task has incomplete dependencies. */
    val isBlocked: Boolean = false,
)

/**
 * Per-task or per-section badge label.
 */
enum class AgendaBadge {
    Overdue,
    Recurring,
    Pinned,
    Completed,
    NoDate,
    Blocked, // MR-1: task has incomplete dependencies
}
