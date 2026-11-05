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
 * The "Add task" action every agenda list of tasks carries: it opens the task
 * editor with no due date.
 *
 * Only the Today tab prefills a date — a task dated today belongs to the Today
 * tab, and one dated tomorrow is the user's call.
 */
private fun addTaskAction(navigate: (AppDestination) -> Unit): FabAction =
    FabAction(
        label = "Add task",
        onClick = {
            navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()))
        },
    )

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
 * Every agenda route that lists tasks offers an "Add task" action: the tabs
 * (**Today**, **Inbox**, **Upcoming**), the Blocked, Stagnant and Overdue views,
 * the drill-downs into one project or one or more tags, and a saved view's
 * results. The saved views' own screens — listing views, editing one and
 * creating one — are not task lists and carry no action.
 *
 * `DestinationKind.tabs` is the source of truth for which destinations are tabs,
 * and `FabActionResolverTest` walks it, so a new agenda tab that resolves to no
 * action fails the suite instead of shipping without a button (#1959). The same
 * test walks every `AgendaStartRoute`, so a new agenda route of any kind has to
 * be classified before it can ship (#2057).
 *
 * When navigating from the **Today** tab, the task creation screen is pre-filled with
 * `dueDate = Today` so the user can change it without having to set it from scratch.
 * When navigating from any other task list, no due date is pre-filled: a task
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

                // Every route the agenda graph can start on is named here rather
                // than folded into a catch-all, so a new [AgendaStartRoute] makes
                // this `when` non-exhaustive: the compiler refuses the build until
                // the route is classified, and a task-listing route can no longer
                // fall through to `null` silently — the shape the Upcoming tab
                // shipped in (#1959).
                //
                // A route that lists tasks carries the action, wherever the user
                // stands on it: the drill-downs into one project or one or more
                // tags, and a saved view's results, are task lists like any other
                // (#2057). The created task is not bound to the project or the tags
                // being viewed — the button says "add a task", not "add a task
                // here" — and it carries no due date, exactly as on the Inbox tab.
                AgendaStartRoute.Inbox,
                AgendaStartRoute.Upcoming,
                AgendaStartRoute.Blocked,
                AgendaStartRoute.Stagnant,
                AgendaStartRoute.Overdue,
                -> addTaskAction(navigate)

                is AgendaStartRoute.Project,
                is AgendaStartRoute.Tag,
                is AgendaStartRoute.Tags,
                is AgendaStartRoute.SavedAgendaResults,
                -> addTaskAction(navigate)

                // The screens that manage saved views are not task lists: listing
                // views, editing one and creating one carry no action. A saved
                // view's *results* are a list of tasks and are classified above.
                AgendaStartRoute.SavedAgendaList,
                AgendaStartRoute.SavedAgendaCreate,
                -> null

                is AgendaStartRoute.SavedAgendaEdit -> null
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
