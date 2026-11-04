package com.singularity.todo.feature.nav

import com.singularity.todo.feature.agenda.domain.model.AgendaCreatePrefill
import com.singularity.todo.feature.tasks.domain.state.DueDateOption

/**
 * The prefill a task started from [this] route's list has to carry, or `null`
 * when no task the editor can create would ever appear in that list.
 *
 * ## Why the route decides, and not the screen
 *
 * An empty agenda list is precisely the case where the rendered sections carry
 * no information: [com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator]
 * drops a section that matched nothing, so the list that offers the button is
 * the list that cannot say what belongs in it. The route is the one thing that
 * still knows.
 *
 * ## The three refusals
 *
 * [AgendaStartRoute.Blocked], [AgendaStartRoute.Stagnant] and
 * [AgendaStartRoute.Overdue] return `null`: a blocked task is blocked by its
 * dependencies, a stagnant one has been untouched for three weeks, and an
 * overdue one is in the past. No editor input produces any of those, so a
 * button there would create a task that never appears — worse than no button.
 *
 * The saved-view routes return `null` too: their screens do not render the
 * agenda body, and their lists are the user's own definition.
 */
fun AgendaStartRoute.toCreatePrefill(): AgendaCreatePrefill? =
    when (this) {
        // The Inbox holds what is not dated, so its task carries no date.
        AgendaStartRoute.Inbox -> AgendaCreatePrefill()

        // A task dated today belongs to Today. The shell's "Add task" FAB
        // prefills the same day (FabActionResolver).
        AgendaStartRoute.Today -> AgendaCreatePrefill(dueDate = DueDateOption.Today)

        // A task dated today belongs to Today, so an *undated* one would land in
        // the Inbox. Tomorrow is the earliest date Upcoming shows. The shell FAB
        // on this tab still opens a blank editor (#2030 owns that divergence).
        AgendaStartRoute.Upcoming -> AgendaCreatePrefill(dueDate = DueDateOption.Tomorrow)

        is AgendaStartRoute.Project -> AgendaCreatePrefill(projectId = projectId)

        is AgendaStartRoute.Tag -> AgendaCreatePrefill(tagIds = setOf(tagId))

        is AgendaStartRoute.Tags -> AgendaCreatePrefill(tagIds = tagIds)

        AgendaStartRoute.Blocked,
        AgendaStartRoute.Stagnant,
        AgendaStartRoute.Overdue,
        AgendaStartRoute.SavedAgendaList,
        is AgendaStartRoute.SavedAgendaResults,
        is AgendaStartRoute.SavedAgendaEdit,
        AgendaStartRoute.SavedAgendaCreate,
        -> null
    }
