package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.notes.NoteEditorScreen
import com.singularity.todo.feature.notes.NotePreviewScreen
import com.singularity.todo.feature.notes.NotesScreen
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileSwitcherScreen
import com.singularity.todo.feature.projects.ProjectDetailScreen
import com.singularity.todo.feature.projects.ProjectEditorScreen
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsScreen
import com.singularity.todo.feature.search.SearchScreen
import com.singularity.todo.feature.settings.SettingsScreen
import com.singularity.todo.feature.statistics.StatisticsScreen
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
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
            ProjectsScreen(
                onNavigateToProject = { id ->
                    nav.navigate(AppDestination.ProjectDetail(id))
                },
                onNavigateToCreateProject = {
                    nav.navigate(AppDestination.ProjectEditor())
                },
            )
        }

        entry<AppDestination.Pomodoro> {
            PomodoroScreen(
                timer = koinInject<PomodoroTimer>(),
                onBack = { nav.goBack() },
            )
        }

        entry<AppDestination.Statistics> {
            StatisticsScreen()
        }

        // ─── Menu destinations ─────────────────────────────────────────────

        entry<AppDestination.Notes> {
            NotesScreen(
                onNavigateToNote = { id -> nav.navigate(AppDestination.NoteView(id)) },
                onNavigateToCreateNote = {
                    nav.navigate(AppDestination.NoteEditor())
                },
            )
        }

        entry<AppDestination.AiChat> {
            ChatScreen()
        }

        entry<AppDestination.Search> {
            SearchScreen()
        }

        entry<AppDestination.Archive> {
            ArchiveScreen()
        }

        entry<AppDestination.Settings> {
            SettingsScreen(
                onNavigateToProfileSwitcher = {
                    nav.navigate(AppDestination.ProfileSwitcher)
                },
            )
        }

        entry<AppDestination.AiUsage> {
            AiUsageScreen()
        }

        entry<AppDestination.ProfileSwitcher> {
            ProfileSwitcherScreen(
                onBack = { nav.goBack() },
            )
        }

        // ─── Sub-routes ────────────────────────────────────────────────────

        entry<AppDestination.NoteView> { route ->
            NotePreviewScreen(
                noteId = route.noteId,
                onBack = { nav.goBack() },
                onEdit = { id -> nav.navigate(AppDestination.NoteEditor(id)) },
                onNavigateToNote = { id -> nav.navigate(AppDestination.NoteView(id)) },
                onNavigateToTask = { id -> nav.navigate(AppDestination.TaskDetail(id)) },
            )
        }

        entry<AppDestination.NoteEditor> { route ->
            NoteEditorScreen(
                noteId = route.noteId,
                onBack = { nav.goBack() },
                onNavigateToNote = { id -> nav.navigate(AppDestination.NoteView(id)) },
                onNavigateToTask = { id -> nav.navigate(AppDestination.TaskDetail(id)) },
            )
        }

        entry<AppDestination.ProjectEditor> { route ->
            ProjectEditorScreen(
                projectId = route.projectId?.let { ProjectId.fromString(it) },
                onBack = { nav.goBack() },
            )
        }

        entry<AppDestination.ProjectDetail> { route ->
            ProjectDetailScreen(
                projectId = ProjectId.fromString(route.projectId),
                onBack = { nav.goBack() },
                onNavigateToTasks = { projectId ->
                    nav.navigate(AppDestination.TasksByProject(projectId.value))
                },
                onNavigateToTask = { taskId ->
                    nav.navigate(AppDestination.TaskDetail(taskId.value))
                },
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
    }
}
