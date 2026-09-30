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
 * The deprecated singletons [AppDestination.Inbox]/[AppDestination.Today] were removed in MR-3
 * (they were dead-code entry registrations that were never reached at runtime — the shell
 * uses AgendaGraph routes instead).
 */
internal fun fabActionForNav3(current: AppDestination, navigate: (AppDestination) -> Unit): FabAction? =
    when (current) {
        // ── Modern routes (Desktop / future Android) ───────────────────────────
        is AppDestination.AgendaGraph -> {
            if (current.start == AgendaStartRoute.Inbox || current.start == AgendaStartRoute.Today) {
                FabAction(
                    label = "Add task",
                    onClick = { navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create)) },
                )
            } else {
                null
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
