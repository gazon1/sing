package com.singularity.todo.shell

import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination

/**
 * Data class holding the FAB action for a given navigation state.
 * Used by both Android and Desktop shells to render their platform-specific FAB.
 *
 * @param label user-visible text label (used for content description and/or FAB text)
 * @param onClick navigation action to perform when FAB is clicked
 */
data class FabAction(val label: String, val onClick: () -> Unit)

/**
 * Returns the [FabAction] for the current navigation destination, or `null` if no FAB should be shown.
 *
 * Covers modern [AppDestination.AgendaGraph] and [AppDestination.ProjectsGraph] routes.
 * The deprecated singletons `AppDestination.Inbox`/`AppDestination.Today` were removed in MR-1
 * (they were dead-code entry registrations that were never reached at runtime — the shell
 * uses AgendaGraph routes instead).
 *
 * ## FAB prefill behaviour
 *
 * Every agenda tab that lists tasks offers an "Add task" action: **Today**,
 * **Inbox** and **Upcoming**, plus the Blocked, Stagnant and Overdue views.
 *
 * The agenda routes that are *not* a list of tasks — the project and tag
 * drill-downs and the saved-view screens — resolve to no action. They are named
 * in the branch rather than caught by an `else`, so adding a route to
 * `AgendaStartRoute` fails to compile until somebody classifies it: a tab that
 * lists tasks cannot silently lose its button (#1959).
 *
 * When navigating from the **Today** tab, the task creation screen is pre-filled with
 * `dueDate = Today` so the user can change it without having to set it from scratch.
 * When navigating from **Inbox** or **Upcoming**, no due date is pre-filled: a task
 * dated today belongs to the Today tab, and one dated tomorrow is the user's call.
 *
 * @param today the date to pre-fill, supplied by the caller. Required rather than
 *   read here (#91): the shell knows the date it is rendering, and a resolver that
 *   read the wall clock would give a prefill that differs from the Today tab the
 *   user just tapped. `FabActionResolverTest` asserts against a named date for the
 *   first time as a result.
 */
internal fun fabActionForNav3(
    current: AppDestination,
    today: kotlinx.datetime.LocalDate,
    navigate: (AppDestination) -> Unit,
): FabAction? =
    when (current) {
        // ── Modern routes (Desktop / future Android) ───────────────────────────
        is AppDestination.AgendaGraph -> {
            when (current.start) {
                AgendaStartRoute.Today -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create(), today))
                        },
                    )
                }

                AgendaStartRoute.Inbox,
                AgendaStartRoute.Upcoming,
                AgendaStartRoute.Blocked,
                AgendaStartRoute.Stagnant,
                AgendaStartRoute.Overdue,
                -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()))
                        },
                    )
                }

                // The agenda routes that are not a list of tasks: the project and tag
                // drill-downs and the saved-view screens. They are named rather than
                // caught by an `else`, so a route added to `AgendaStartRoute` is a
                // compile error here instead of a tab that silently loses its button
                // (#1959). A route that does list tasks belongs in the branch above.
                is AgendaStartRoute.Project,
                is AgendaStartRoute.Tag,
                is AgendaStartRoute.Tags,
                AgendaStartRoute.SavedAgendaList,
                is AgendaStartRoute.SavedAgendaResults,
                is AgendaStartRoute.SavedAgendaEdit,
                AgendaStartRoute.SavedAgendaCreate,
                -> {
                    null
                }
            }
        }

        is AppDestination.ProjectsGraph -> {
            FabAction(
                label = "Add project",
                onClick = { navigate(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor())) },
            )
        }

        // The plan tab creates a *task*, not a project: the timeline shows the
        // work that is ahead, and a task due today is the piece that belongs in
        // the first section of it.
        AppDestination.Plans -> {
            FabAction(
                label = "Add task",
                onClick = {
                    navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create(), today))
                },
            )
        }

        // NotesNavGraph has its own note creation button — no shell FAB needed here.
        else -> {
            null
        }
    }
