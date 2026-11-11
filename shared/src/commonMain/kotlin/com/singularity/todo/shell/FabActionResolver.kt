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
 * Covers every [AppDestination]. The ones that carry an action are [AppDestination.AgendaGraph]
 * (delegating to [fabActionForAgendaStart]) and [AppDestination.Plans]; the rest resolve to
 * `null`, each for the reason written beside its branch.
 * The deprecated singletons `AppDestination.Inbox`/`AppDestination.Today` were removed in MR-1
 * (they were dead-code entry registrations that were never reached at runtime — the shell
 * uses AgendaGraph routes instead).
 *
 * Every member of [AppDestination] is classified: no `else`, so a destination added to
 * the hierarchy does not compile until someone decides whether the shell gives it a
 * button (#2423). A catch-all is how the agenda's drill-downs once came to share a
 * glyph, and here it would give a new destination a silent "no FAB".
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
 * action: the list, the results, the editor and the creator resolve to `null`.
 * The results screen is the contested one — it does show tasks, but it shows
 * the tasks a *view* selects, and [fabActionForAgendaStart] says why the button does
 * not belong there (#1952, #1958).
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
        // ── Destinations that carry the shell action ───────────────────────────
        is AppDestination.AgendaGraph -> {
            fabActionForAgendaStart(current.start, today, navigate)
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

        // ── Destinations with no shell action, each for its own reason ─────────

        // Every screen inside the projects graph carries its own create action:
        // the list has a `+` in its bar, the editor has Save there, and the
        // detail has an inline task composer. A shell FAB here would duplicate
        // one of them and miss the others (#2735).
        is AppDestination.ProjectsGraph -> {
            null
        }

        // Already inside the tasks: the editor or the detail *is* the work, so
        // there is no new task to add from here.
        is AppDestination.TasksGraph -> {
            null
        }

        // Documented unwired surface: no `entry<>` and no production opener
        // (`docs/navigation.md`), so nothing renders a FAB over it.
        is AppDestination.TasksByProject -> {
            null
        }

        // Record screens carry their own controls: Save in the project editor, an
        // inline task composer on the project detail, and the attachment viewer
        // only displays.
        is AppDestination.ProjectEditor,
        is AppDestination.ProjectDetail,
        is AppDestination.AttachmentViewer,
        -> {
            null
        }

        // The notes graph has its own note creation button.
        AppDestination.Notes,
        is AppDestination.NotesGraph,
        -> {
            null
        }

        // A month grid is not a list of tasks; the calendar's selection bar
        // creates the task for the day the user picked, which a shell FAB
        // would not know.
        AppDestination.Calendar,
        is AppDestination.CalendarGraph,
        -> {
            null
        }

        // The archive holds finished work; a task created here would belong in the
        // Inbox or a list, not among the done ones.
        AppDestination.Archive -> {
            null
        }

        // The timer, the charts and the settings read or configure existing work
        // rather than start it.
        AppDestination.Pomodoro,
        AppDestination.Statistics,
        AppDestination.AiUsage,
        AppDestination.Settings,
        AppDestination.ProfileSwitcher,
        -> {
            null
        }

        // The assistant, the search and the proposals act on what exists; the
        // external agent is a third-party process that brings its own surface.
        AppDestination.AiChat,
        AppDestination.Search,
        AppDestination.Proposals,
        AppDestination.ExternalAgent,
        -> {
            null
        }

        // Forms with their own submit button.
        AppDestination.AttachAccount,
        AppDestination.ChangePassword,
        is AppDestination.MagicLinkConfirm,
        -> {
            null
        }
    }

/**
 * The action for an agenda route, or `null` for a route that is not a task list.
 *
 * Every member of [AgendaStartRoute] is classified: no `else`, so a route added to
 * the hierarchy does not compile until someone decides whether the screen it opens
 * lists tasks (#1959).
 *
 * ## The saved-view routes carry no action
 *
 * Saved-view management is not a task list: listing views, viewing, editing and
 * creating a view all keep no button.
 *
 * `SavedAgendaResults` is classified here too, and that is a resolved contradiction
 * rather than an oversight (#1952 and #1958 versus #2057). #2057 puts "the route
 * showing a saved view's results" among the task-listing routes and asks for an
 * "Add task" action there. #1952 — the brief this change was opened against — asks
 * the opposite, in so many words: "the saved-view agenda routes still draw no
 * button, and a test says so". #1958 says the same thing twice, once as "the
 * saved-views list, the saved-view editor and the saved-view results keep no shell
 * button" and once in its out-of-scope line, which leaves "the saved-views screens
 * and their own create button" alone. Two briefs to one is why this branch stays
 * `null`: the results screen is a saved view applied to the agenda, and the view
 * owns the way a task is added to it. A button here would create a task that
 * satisfies no part of the view's own query — it would appear in the list only if
 * it happened to match, which is not what the button promises.
 *
 * If #2057 is meant to win, move `SavedAgendaResults` into the "Add task" branch
 * below *and* flip it in `FabActionResolverTest.expectedAddTask` — the resolver and
 * that classification are the same decision and move together, or the suite fails.
 */
private fun fabActionForAgendaStart(
    start: AgendaStartRoute,
    today: kotlinx.datetime.LocalDate,
    navigate: (AppDestination) -> Unit,
): FabAction? =
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

        AgendaStartRoute.SavedAgendaList,
        is AgendaStartRoute.SavedAgendaResults,
        is AgendaStartRoute.SavedAgendaEdit,
        AgendaStartRoute.SavedAgendaCreate,
        -> {
            null
        }
    }
