package com.singularity.todo.feature.agenda.domain.model
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

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
    val tasks: ImmutableList<AgendaRowItem>,
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
 * @param markings Everything the task is marked with — blocked, follow-up, in
 *   progress, next up, overdue, no date, pinned, repeating, completed.
 *
 *   It is a **set**, not a single badge. The row used to carry one badge,
 *   chosen by a fixed priority, and a task that was both blocked and overdue
 *   showed one of the two and lost the other. The markings are computed by
 *   the one shared policy, so the agenda and the task list answer the same
 *   question the same way.
 *
 * @see com.singularity.todo.feature.tasks.domain.logic.computeTaskMarkings
 */
data class AgendaRowItem(
    val task: com.singularity.todo.feature.tasks.domain.model.Task,
    val markings: ImmutableSet<TaskMarking> = persistentSetOf(),
    /** How many dates the series of this row has; null when it stands alone. */
    val seriesDates: Int? = null,
    /**
     * The task's project, resolved once per list build — name and colour.
     *
     * The row cannot resolve it itself: a lookup per row would be one repository
     * read per row, and it would make the row unpreviewable. Both are resolved in
     * the ViewModel, from a projects flow that includes **closed** projects, so a
     * row whose project was closed keeps its badge.
     */
    val project: String? = null,
    val projectColor: Int? = null,
)
