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
 * The saved-view screens are not task lists, and none of them carries a shell
 * action: the list, the editor and the creator resolve to `null`, and so does the
 * saved-view *results* screen. That last one is a resolved contradiction, not an
 * omission — the briefs this change answers disagree about it, and the branch
 * below records which way and why (#1952 and #1958 keep it buttonless, #2057 asks
 * for a button).
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

                // Saved-view management is not a task list: listing views,
                // editing a view and creating one all keep no button.
                //
                // SavedAgendaResults is classified here too, and that is a
                // resolved contradiction rather than an oversight (#1952 and
                // #1958 versus #2057). #2057 puts "the route showing a saved
                // view's results" among the task-listing routes and asks for an
                // "Add task" action there. #1952 — the brief this change was
                // opened against — asks the opposite, in so many words: "the
                // saved-view agenda routes still draw no button, and a test
                // says so". #1958 says the same thing twice, once as "the
                // saved-views list, the saved-view editor and the saved-view
                // results keep no shell button" and once in its out-of-scope
                // line, which leaves "the saved-views screens and their own
                // create button" alone. Two briefs to one is why this branch
                // stays `null`: the results screen is a saved view applied to
                // the agenda, and the view owns the way a task is added to it.
                // If #2057 is meant to win, move SavedAgendaResults into the
                // "Add task" branch above *and* flip it in
                // `FabActionResolverTest.expectedAddTask` — the resolver and
                // that classification are the same decision and move together,
                // or the suite fails.
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
