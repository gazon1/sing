package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.presentation.nav.NotesNavGraph
import com.singularity.todo.feature.notes.presentation.nav.NotesRoute
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileSwitcherScreen
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavGraph
import com.singularity.todo.feature.projects.presentation.nav.ProjectsRoute
import com.singularity.todo.feature.search.presentation.nav.SearchNavGraph
import com.singularity.todo.feature.settings.presentation.nav.SettingsNavGraph
import com.singularity.todo.feature.statistics.StatisticsScreen
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
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
fun createAppEntryProvider(nav: NavCallbacks): (AppDestination) -> NavEntry<AppDestination> {
    return entryProvider {
        // ─── Top-level tabs ────────────────────────────────────────────────

        entry<AppDestination.Inbox> {
            TasksNavGraph(
                start = TasksRoute.Inbox(),
                onExitGraph = { dest ->
                    when (dest) {
                        is AppDestination.ProjectDetail -> nav.navigate(dest)
                        else -> nav.goBack()
                    }
                },
            )
        }

        entry<AppDestination.Today> {
            TasksNavGraph(
                start = TasksRoute.Today(),
                onExitGraph = { dest ->
                    when (dest) {
                        is AppDestination.ProjectDetail -> nav.navigate(dest)
                        else -> nav.goBack()
                    }
                },
            )
        }

        entry<AppDestination.Plans> {
            ProjectsNavGraph(
                start = ProjectsRoute.List,
                onExitGraph = { nav.goBack() },
            )
        }

        entry<AppDestination.Pomodoro> {
            PomodoroScreen(
                timer = koinInject<PomodoroTimer>(),
            )
        }

        entry<AppDestination.Statistics> {
            StatisticsScreen()
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
            ArchiveScreen()
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
            ProfileSwitcherScreen()
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
                        is AppDestination.TasksByProject -> nav.navigate(dest)
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
            TasksNavGraph(
                start = TasksRoute.ByProject(ProjectId.fromString(route.projectId)),
                onExitGraph = { dest ->
                    when (dest) {
                        is AppDestination.ProjectDetail -> nav.navigate(dest)
                        else -> nav.goBack()
                    }
                },
            )
        }

        // TasksGraph entry: converts TasksStartRoute to TasksRoute for the inner graph
        entry<AppDestination.TasksGraph> { route ->
            TasksNavGraph(
                start = route.start.toTasksRoute(route.initialDueDate),
                onExitGraph = { dest ->
                    when (dest) {
                        is AppDestination.ProjectDetail -> nav.navigate(dest)
                        else -> nav.goBack()
                    }
                },
            )
        }

        // NotesGraph entry: converts NotesStartRoute to NotesRoute for the inner graph
        entry<AppDestination.NotesGraph> { route ->
            NotesNavGraph(
                navCallbacks = nav,
                start = route.start.toNotesRoute(),
            )
        }
    }
}

/** Converts [AppDestination.TasksStartRoute] to the inner [TasksRoute]. */
private fun AppDestination.TasksStartRoute.toTasksRoute(
    initialDueDate: LocalDate?,
): TasksRoute = when (this) {
    is AppDestination.TasksStartRoute.Inbox -> TasksRoute.Inbox()
    is AppDestination.TasksStartRoute.Today -> TasksRoute.Today()
    is AppDestination.TasksStartRoute.Create -> TasksRoute.Create(initialDueDate)
    is AppDestination.TasksStartRoute.ByProject -> TasksRoute.ByProject(
        ProjectId.fromString(projectId),
    )
    is AppDestination.TasksStartRoute.Detail -> TasksRoute.Detail(
        TaskId.fromString(taskId),
    )
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
}
