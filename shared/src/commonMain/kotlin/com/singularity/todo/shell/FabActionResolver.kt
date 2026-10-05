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
 * When navigating from the **Today** tab, the task creation screen is pre-filled with
 * `dueDate = Today` so the user can change it without having to set it from scratch.
 * When navigating from **Inbox**, no due date is pre-filled.
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
                AgendaStartRoute.Today -> FabAction(
                    label = "Add task",
                    onClick = {
                        navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create(), today))
                    },
                )

                AgendaStartRoute.Inbox -> FabAction(
                    label = "Add task",
                    onClick = {
                        navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()))
                    },
                )

                else -> null
            }
        }

        is AppDestination.ProjectsGraph -> {
            FabAction(
                label = "Add project",
                onClick = { navigate(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor())) },
            )
        }

        AppDestination.Plans -> FabAction(
            label = "Add project",
            onClick = { navigate(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor())) },
        )

        // NotesNavGraph has its own note creation button — no shell FAB needed here.
        else -> null
    }
