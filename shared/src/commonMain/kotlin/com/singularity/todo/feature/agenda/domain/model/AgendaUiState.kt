package com.singularity.todo.feature.agenda.domain.model
import com.singularity.todo.feature.tasks.domain.model.BlockedBulkCloseRequest
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskRowItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate

/**
 * UI state for the Agenda screen.
 *
 * @param sections The evaluated and rendered sections with their tasks.
 * @param today The current date at the time of evaluation, used to label relative buckets.
 * @param isSelectionMode true when the user is in multi-selection mode.
 * @param selectedTaskIds The set of task IDs currently selected.
 * @param pendingBlockedClose A task whose prerequisites are still open, waiting for the
 *        user to confirm closing it anyway. Null when no confirmation is pending.
 * @param pendingBlockedBulkClose A selection in which some tasks have open prerequisites,
 *        waiting for the user to confirm closing the selection anyway. Null when no
 *        confirmation is pending.
 *
 * ## Why there is no `@Immutable` here
 *
 * The state of a whole screen is compared by identity, and that is the cheap
 * and honest thing to do: an `equals` over every section of the agenda would
 * walk every task in it, on every recomposition of the screen.
 *
 * The class used to carry `@Immutable`, which made the compiler compare it by
 * `equals` — the deep comparison the annotation's name suggests and the one
 * nobody wants at screen level. The screen is cheap to re-run and expensive to
 * compare; the *leaves* are the other way round, and they are what carry the
 * stability. See docs/architecture/compose-stability.md.
 */
sealed interface AgendaUiState {
    data object Loading : AgendaUiState
    data class Loaded(
        val sections: ImmutableList<RenderedSection>,
        val today: LocalDate,
        val greeting: String = "",
        val isSelectionMode: Boolean = false,
        val selectedTaskIds: ImmutableSet<TaskId> = persistentSetOf(),
        val pendingBlockedClose: BlockedCloseRequest? = null,
        val pendingBlockedBulkClose: BlockedBulkCloseRequest? = null,
    ) : AgendaUiState
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
 *
 * ## The section is the comparison boundary
 *
 * [tasks] is an [ImmutableList] so that a section is *stable*: the compiler
 * compares it by `equals`, and the comparison stops at the section. A screen
 * that holds ten sections is compared once, by identity; a section that holds
 * twenty tasks is compared by content, once.
 *
 * `ImmutableList` is a known-stable construct for the Compose compiler — unlike
 * `List`, which is an interface the compiler cannot reason about — and its
 * `equals` compares structure rather than identity, so an unchanged section
 * compares equal and the section is skipped.
 */
data class RenderedSection(
    val id: String,
    val name: String,
    val tasks: ImmutableList<TaskRowItem>,
    val badge: Int? = null,
    val prefill: SectionPrefill? = null,
)
