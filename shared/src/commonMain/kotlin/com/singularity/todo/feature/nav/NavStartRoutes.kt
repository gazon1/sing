package com.singularity.todo.feature.nav

import kotlinx.datetime.LocalDate

/*
 * Start-route → inner-graph-route conversion, shared by both platform entry providers.
 *
 * These four `when` expressions used to be duplicated verbatim as `private` helpers in
 * `AndroidNavEntries.kt` and `JvmNavEntries.kt`. That is the dangerous kind of duplication:
 * each `when` is exhaustive over its own sealed hierarchy, so adding a variant to one file
 * and forgetting the other **compiles cleanly in both places** and only diverges at runtime,
 * as a screen that opens the wrong graph. The exhaustiveness check fires for the file it is
 * in, and the two files had separate `private` functions, so nothing noticed.
 *
 * The entry *bodies* stay per-platform on purpose — Android lets each graph own its stack
 * (`rememberNavBackStackTyped`, saved-state backed) while JVM hoists top-level stacks into
 * the shell so they survive an entry leaving `NavDisplay`'s visible set. Merging those is a
 * design change, not a mechanical one; see the addendum in ADR
 * `2026-10-02-nav-entries-dedup-deferred`. The conversions below are not
 * platform-dependent, so they live here.
 */

/** Converts [AppDestination.TasksStartRoute] to the inner [TasksRoute]. */
internal fun AppDestination.TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute = when (this) {
    is AppDestination.TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)
    is AppDestination.TasksStartRoute.Detail -> TasksRoute.Detail(taskId)
}

/** Converts [AppDestination.ProjectsStartRoute] to the inner [ProjectsRoute]. */
internal fun AppDestination.ProjectsStartRoute.toProjectsRoute(): ProjectsRoute = when (this) {
    is AppDestination.ProjectsStartRoute.List -> ProjectsRoute.List
    is AppDestination.ProjectsStartRoute.Editor -> ProjectsRoute.Editor(projectId)
}

/** Converts [AppDestination.NotesStartRoute] to the inner [NotesRoute]. */
internal fun AppDestination.NotesStartRoute.toNotesRoute(): NotesRoute = when (this) {
    is AppDestination.NotesStartRoute.List -> NotesRoute.List

    is AppDestination.NotesStartRoute.Preview -> NotesRoute.Preview(noteId)

    is AppDestination.NotesStartRoute.EditorForTask -> NotesRoute.Editor(
        noteId = null,
        taskId = taskId,
    )
}

/** Converts [AppDestination.CalendarStartRoute] to the inner [CalendarRoute]. */
internal fun AppDestination.CalendarStartRoute.toCalendarRoute(): CalendarRoute = when (this) {
    is AppDestination.CalendarStartRoute.Month -> CalendarRoute.Month(anchor)
}
