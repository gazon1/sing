package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.calendar.presentation.nav.CalendarNavGraph
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.nav.ProjectsRoute
import com.singularity.todo.feature.nav.TasksRoute
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
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Creates the app-wide entry provider using the terrakok nav3-recipes pattern.
 *
 * Uses `androidx.navigation3.runtime.entryProvider { }` DSL (NOT koin's `navigation {}` DSL)
 * to manually build `NavEntry<AppDestination>` objects. Inside each entry's `@Composable`
 * content, `koinViewModel()` works normally.
 *
 * This bypasses the koin `navigation {}` DSL classpath conflict where the multiplatform
 * metadata JAR (`koin-compose-navigation3`) shadows the platform-specific implementation.
 */
@Composable
fun createAppEntryProvider(nav: NavCallbacks): (AppDestination) -> NavEntry<AppDestination> = entryProvider {
    // ─── Top-level tabs ────────────────────────────────────────────────

    // Inbox, Today, Upcoming are handled by the catch-all AgendaGraph entry below.
    // Each variant maps to the corresponding AgendaStartRoute (Inbox/Today/Upcoming).

    entry<AppDestination.Plans> {
        ProjectsNavGraph(
            start = ProjectsRoute.List,
            onExitGraph = nav.graphExit,
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
            onExitGraph = nav.graphExit,
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
            onExitGraph = nav.graphExit,
        )
    }

    entry<AppDestination.ProjectDetail> { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Detail(ProjectId.fromString(route.projectId)),
            onExitGraph = nav.graphExit,
        )
    }

    entry<AppDestination.ProjectsGraph> { route ->
        ProjectsNavGraph(
            start = route.start.toProjectsRoute(),
            onExitGraph = nav.graphExit,
        )
    }

    // TasksGraph entry: converts TasksStartRoute to TasksRoute for the inner graph
    entry<AppDestination.TasksGraph> { route ->
        TasksNavGraph(
            start = route.start.toTasksRoute(route.initialDueDate),
            onExitGraph = nav.graphExit,
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
            onExitGraph = nav.graphExit,
        )
    }

    // AgendaGraph entry
    entry<AppDestination.AgendaGraph> { route ->
        AgendaNavGraph(
            start = route.start.toAgendaStartRoute(),
            onExitGraph = nav.graphExit,
        )
    }
}

/**
 * Converts [AppDestination.TasksStartRoute] to the inner [TasksRoute].
 * Deprecated [Inbox] and [Upcoming] map to [Create] for backwards compatibility.
 */
private fun AppDestination.TasksStartRoute.toTasksRoute(initialDueDate: LocalDate?): TasksRoute = when (this) {
    is AppDestination.TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)
    is AppDestination.TasksStartRoute.Detail -> TasksRoute.Detail(taskId)
}

/** Converts [AppDestination.ProjectsStartRoute] to the inner [ProjectsRoute]. */
private fun AppDestination.ProjectsStartRoute.toProjectsRoute(): ProjectsRoute = when (this) {
    is AppDestination.ProjectsStartRoute.List -> ProjectsRoute.List
    is AppDestination.ProjectsStartRoute.Editor -> ProjectsRoute.Editor(projectId)
}

/** Converts [AppDestination.NotesStartRoute] to the inner [NotesRoute]. */
private fun AppDestination.NotesStartRoute.toNotesRoute(): NotesRoute = when (this) {
    is AppDestination.NotesStartRoute.List -> NotesRoute.List

    is AppDestination.NotesStartRoute.Preview -> NotesRoute.Preview(noteId)

    is AppDestination.NotesStartRoute.EditorForTask -> NotesRoute.Editor(
        noteId = null,
        taskId = taskId,
    )
}

/** Converts [AppDestination.CalendarStartRoute] to the inner [CalendarRoute]. */
private fun AppDestination.CalendarStartRoute.toCalendarRoute(): CalendarRoute = when (this) {
    is AppDestination.CalendarStartRoute.Month -> CalendarRoute.Month(anchor)
}

/** Converts [AgendaStartRoute] to itself (no conversion needed — same type). */
private fun AgendaStartRoute.toAgendaStartRoute(): AgendaStartRoute = this
