package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.state.DueDateOption

/**
 * What an agenda list knows about a task started from it.
 *
 * The agenda does not create the task: it writes a draft and points the editor
 * at it. This is the part of that draft the *list* decides — the project or the
 * tags its selector filters by, and the date bucket its rows live in. Without
 * it the editor opens blank, and the task it creates is one the list that
 * offered the button can never show: an empty project list stays empty after
 * "Create a task".
 *
 * The prefill is carried by the route, not derived from the rendered sections:
 * the empty state is exactly the case where there are no sections to ask.
 *
 * @see com.singularity.todo.feature.agenda.domain.model.SectionPrefill
 */
data class AgendaCreatePrefill(
    val projectId: ProjectId? = null,
    val tagIds: Set<TagId> = emptySet(),
    /**
     * The date bucket the created task belongs in.
     *
     * Relative, not absolute: [DueDateOption.Today] is the day of the *save*,
     * so a draft written this evening and saved after midnight still lands on
     * the day the user meant — the same reason [SectionPrefill] carries buckets
     * rather than dates.
     */
    val dueDate: DueDateOption = DueDateOption.None,
)
