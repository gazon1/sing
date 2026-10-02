package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.calendar.presentation.nav.CalendarNavGraph
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.presentation.nav.NotesNavGraph
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileSwitcherScreen
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavGraph
import com.singularity.todo.feature.search.presentation.nav.SearchNavGraph
import com.singularity.todo.feature.settings.presentation.nav.SettingsNavGraph
import com.singularity.todo.feature.statistics.StatisticsScreen
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Creates the app-wide entry provider for JVM Desktop, using the same [entryProvider] DSL
 * as Android.
 *
 * The returned lambda is stable across recompositions. Inside each [entry][entryProvider.entry]
 * block, [rememberInMemoryNavBackStack] is called to create the NavBackStack for nested graphs.
 * Because the entry { } content is a @Composable lambda, [remember] is stable across
 * recomposition of the entry's content — the stack is created once per route entry,
 * preventing the "nested stack lost on tab switch" bug.
 *
 * [rememberInMemoryNavBackStack] uses the seed route as its remember key. For nested
 * graphs (Agenda, Tasks, Calendar), passing `stack.lastOrNull() ?: route.start` ensures
 * the graph always uses the current top of the stack as its seed, preserving nested
 * navigation state when the parent recomposes.
 */
@Composable
fun createJvmEntryProvider(nav: NavCallbacks): (AppDestination) -> NavEntry<AppDestination> = entryProvider {
    // ─── Top-level tabs ────────────────────────────────────────────────

    entry<AppDestination.Plans> {
        ProjectsNavGraph(
            start = ProjectsRoute.List,
            onExitGraph = { nav.goBack() },
        )
    }

    entry<AppDestination.Pomodoro> {
        PomodoroScreen(
            timer = koinInject<PomodoroTimer>(),
            taskListProvider = koinInject<PomodoroTaskListProvider>(),
        )
    }

    entry<AppDestination.Statistics> {
        StatisticsScreen()
    }

    entry<AppDestination.Calendar> {
        CalendarNavGraph(
            start = CalendarRoute.Month(
                todayInSystemZone().toString(),
            ),
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.TasksGraph -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
        )
    }

    // ─── Menu destinations ─────────────────────────────────────────────

    entry<AppDestination.Notes> {
        NotesNavGraph(
            navCallbacks = nav,
            start = NotesRoute.List,
        )
    }

    entry<AppDestination.AiChat> {
        ChatScreen()
    }

    entry<AppDestination.Search> {
        SearchNavGraph(
            navCallbacks = nav,
        )
    }

    entry<AppDestination.Archive> {
        ArchiveScreen(onBack = { nav.goBack() })
    }

    entry<AppDestination.Settings> {
        SettingsNavGraph(
            navCallbacks = nav,
        )
    }

    entry<AppDestination.AiUsage> {
        AiUsageScreen()
    }

    entry<AppDestination.ProfileSwitcher> {
        ProfileSwitcherScreen(onBack = { nav.goBack() })
    }

    // ─── Sub-routes ────────────────────────────────────────────────────

    entry<AppDestination.ProjectEditor> { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Editor(route.projectId?.let { ProjectId.fromString(it) }),
            onExitGraph = { nav.goBack() },
        )
    }

    entry<AppDestination.ProjectDetail> { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Detail(ProjectId.fromString(route.projectId)),
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.AgendaGraph -> nav.navigate(dest)
                    is AppDestination.TasksGraph -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
        )
    }

    entry<AppDestination.ProjectsGraph> { route ->
        ProjectsNavGraph(
            start = route.start.toProjectsRoute(),
            onExitGraph = { nav.goBack() },
        )
    }

    entry<AppDestination.TasksByProject> { route ->
        val agendaStack: NavBackStack<AgendaStartRoute> =
            rememberInMemoryNavBackStack(AgendaStartRoute.Project(route.projectId))
        AgendaNavGraph(
            start = agendaStack.lastOrNull() ?: AgendaStartRoute.Project(route.projectId),
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.ProjectDetail -> nav.navigate(dest)
                    is AppDestination.TasksGraph -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
            backStack = agendaStack,
        )
    }

    // TasksGraph entry: converts TasksStartRoute to TasksRoute for the inner graph
    entry<AppDestination.TasksGraph> { route ->
        // Seed with the REQUESTED route, not a hardcoded one: rememberInMemoryNavBackStack
        // uses the seed as its remember key, so a hardcoded seed makes the
        // `lastOrNull() ?: route.start...` fallback dead code and every navigation
        // (e.g. Detail(taskId) from the agenda) lands on the hardcoded screen.
        val tasksStack: NavBackStack<TasksRoute> =
            rememberInMemoryNavBackStack(route.start.toTasksRoute(route.initialDueDate))
        TasksNavGraph(
            start = tasksStack.lastOrNull() ?: route.start.toTasksRoute(route.initialDueDate),
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.ProjectDetail -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
            backStack = tasksStack,
        )
    }

    // NotesGraph entry: converts NotesStartRoute to NotesRoute for the inner graph
    entry<AppDestination.NotesGraph> { route ->
        NotesNavGraph(
            navCallbacks = nav,
            start = route.start.toNotesRoute(),
        )
    }

    // CalendarGraph entry
    entry<AppDestination.CalendarGraph> { route ->
        CalendarNavGraph(
            start = route.start.toCalendarRoute(),
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.TasksGraph -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
        )
    }

    // AgendaGraph entry
    entry<AppDestination.AgendaGraph> { route ->
        val agendaStack: NavBackStack<AgendaStartRoute> = rememberInMemoryNavBackStack(route.start)
        AgendaNavGraph(
            start = agendaStack.lastOrNull() ?: route.start,
            onExitGraph = { dest ->
                when (dest) {
                    is AppDestination.ProjectDetail -> nav.navigate(dest)
                    is AppDestination.TasksGraph -> nav.navigate(dest)
                    else -> nav.goBack()
                }
            },
            backStack = agendaStack,
        )
    }
}

/**
 * Converts [AppDestination.TasksStartRoute] to the inner [TasksRoute].
 *
 * Note: Inbox/Today/Upcoming/ByProject are deprecated (AgendaEngine MR1).
 * These variants no longer have corresponding routes in TasksNavGraph — they fall back
 * to [TasksRoute.Create] so the user at least sees a valid screen.
 */
private fun AppDestination.TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute = when (this) {
    is AppDestination.TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)

    is AppDestination.TasksStartRoute.Detail -> TasksRoute.Detail(TaskId.fromString(taskId))

    // Deprecated variants: fall back to Create
    else -> TasksRoute.Create(initialDueDate)
}

/** Converts [AppDestination.ProjectsStartRoute] to the inner [ProjectsRoute]. */
private fun AppDestination.ProjectsStartRoute.toProjectsRoute(): ProjectsRoute = when (this) {
    is AppDestination.ProjectsStartRoute.List -> ProjectsRoute.List

    is AppDestination.ProjectsStartRoute.Editor -> ProjectsRoute.Editor(
        projectId?.let { ProjectId.fromString(it) },
    )
}

/** Converts [AppDestination.NotesStartRoute] to the inner [NotesRoute]. */
private fun AppDestination.NotesStartRoute.toNotesRoute(): NotesRoute = when (this) {
    is AppDestination.NotesStartRoute.List -> NotesRoute.List

    is AppDestination.NotesStartRoute.Preview -> NotesRoute.Preview(
        NoteId.fromString(noteId),
    )

    is AppDestination.NotesStartRoute.EditorForTask -> NotesRoute.Editor(
        noteId = null,
        taskId = TaskId.fromString(taskId),
    )
}

/** Converts [AppDestination.CalendarStartRoute] to the inner [CalendarRoute]. */
private fun AppDestination.CalendarStartRoute.toCalendarRoute(): CalendarRoute = when (this) {
    is AppDestination.CalendarStartRoute.Month -> CalendarRoute.Month(anchor)
}
