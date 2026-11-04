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
 * Every agenda screen that lists tasks offers an "Add task" action: **Today**,
 * **Inbox**, **Upcoming**, the Blocked, Stagnant and Overdue views, and the
 * project and tag drill-downs.
 *
 * When navigating from the **Today** tab, the task creation screen is pre-filled with
 * `dueDate = Today` so the user can change it without having to set it from scratch.
 * When navigating from **Inbox** or **Upcoming**, no due date is pre-filled: a task
 * dated today belongs to the Today tab, and one dated tomorrow is the user's call.
 *
 * The project drill-down seeds the new task with that project, and the single-tag
 * and multi-tag drill-downs with that tag or tags, so the created task belongs to
 * the list it was created from.
 *
 * The saved-view management screens are not task lists, and none of them carries
 * a shell action: the list, the editor and the creator resolve to `null`. The
 * saved view's *results* screen does carry one, because it is a list of tasks —
 * see the branch below for why it is classified that way rather than with the
 * management screens (#2057, against #1952/#1958).
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
            // Every member is classified: no `else`, so a route added to the
            // hierarchy does not compile until someone decides whether the
            // screen it opens lists tasks (#1959).
            val start = current.start
            when (start) {
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

                is AgendaStartRoute.Project -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(
                                AppDestination.TasksGraph(
                                    AppDestination.TasksStartRoute.Create(projectId = start.projectId),
                                ),
                            )
                        },
                    )
                }

                is AgendaStartRoute.Tag -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(
                                AppDestination.TasksGraph(
                                    AppDestination.TasksStartRoute.Create(tagIds = setOf(start.tagId)),
                                ),
                            )
                        },
                    )
                }

                is AgendaStartRoute.Tags -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(
                                AppDestination.TasksGraph(
                                    AppDestination.TasksStartRoute.Create(tagIds = start.tagIds),
                                ),
                            )
                        },
                    )
                }

                // A saved view's *results* screen is a list of tasks, so it gets
                // the same action the tabs get: plain, no date (#2057). The
                // management screens — listing views, editing one, creating one —
                // are not task lists and keep no button; #1952, #1958 and #2057
                // agree on those three.
                //
                // #1952 and #1958 asked for the results screen to stay buttonless
                // too; #2057 came later and put it among the task-listing routes,
                // which is what it is — the saved view is a filter applied to the
                // same agenda, and a person standing on it sees tasks. The batched
                // briefs cannot all be met for this one route, and the newest
                // classification wins. `FabActionResolverTest.expectedAddTask`
                // holds the same decision from the test side: the resolver and
                // that table are one decision and must move together, or the
                // suite fails.
                is AgendaStartRoute.SavedAgendaResults -> {
                    FabAction(
                        label = "Add task",
                        onClick = {
                            navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()))
                        },
                    )
                }

                AgendaStartRoute.SavedAgendaList,
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
