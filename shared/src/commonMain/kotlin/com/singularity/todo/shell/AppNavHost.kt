package com.singularity.todo.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavigator
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
import com.singularity.todo.feature.tasks.TaskDetailScreen
import com.singularity.todo.feature.tasks.TaskEditorScreen
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TasksByProjectScreen
import com.singularity.todo.feature.tasks.TasksScreen
import com.singularity.todo.feature.tasks.TasksScreenEntry
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The single navigation graph for the app.
 *
 * - **Tabs** (bottom-bar / drawer entries) are top-level destinations.
 * - **Sub-routes** (TaskDetail, TaskEditor, NoteEditor, ProjectEditor,
 *   ProjectDetail) push on top of a tab and don't appear in the bottom bar.
 *
 * Why a single function instead of inline NavHost in `AndroidShell`:
 * - Keeps the graph definition in one place — testable + greppable.
 * - `AndroidShell` stays focused on chrome (Scaffold + bottom bar + FAB).
 * - Desktop and Android can share the same graph — only the chrome differs.
 */
@Composable
fun AppNavHost(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navigator.controller,
        startDestination = AppDestination.Today,
        modifier = modifier,
    ) {
        // ─── Top-level tabs ─────────────────────────────────────────────────
        composable<AppDestination.Today> {
            TasksRoute(entry = TasksScreenEntry.FromToday, navigator = navigator)
        }
        composable<AppDestination.Inbox> {
            TasksRoute(entry = TasksScreenEntry.FromInbox, navigator = navigator)
        }
        composable<AppDestination.Plans> {
            ProjectsScreen(
                onNavigateToProject = { id ->
                    navigator.navigate(AppDestination.ProjectDetail(id))
                },
                onNavigateToCreateProject = {
                    navigator.navigate(AppDestination.ProjectEditor())
                },
            )
        }
        composable<AppDestination.Habits> {
            PomodoroScreen(timer = koinInject<PomodoroTimer>(), onBack = navigator::popBackStack)
        }
        composable<AppDestination.Calendar> {
            StatisticsScreen()
        }

        // ─── Menu destinations (opened via MenuBottomSheet) ─────────────────
        composable<AppDestination.Notes> {
            NotesRoute(navigator = navigator)
        }
        composable<AppDestination.AiChat> {
            ChatScreen()
        }
        composable<AppDestination.Search> {
            SearchScreen()
        }
        composable<AppDestination.Archive> {
            ArchiveScreen()
        }
        composable<AppDestination.Settings> {
            SettingsScreen(
                onNavigateToProfileSwitcher = {
                    navigator.navigate(AppDestination.ProfileSwitcher)
                },
            )
        }
        composable<AppDestination.AiUsage> {
            AiUsageScreen()
        }
        composable<AppDestination.ProfileSwitcher> {
            ProfileSwitcherScreen(onBack = navigator::popBackStack)
        }

        // ─── Sub-routes (push on top of a tab) ──────────────────────────────
        composable<AppDestination.TaskDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.TaskDetail>()
            TaskDetailScreen(
                taskId = TaskId.fromString(route.taskId),
                onBack = navigator::popBackStack,
                onNavigateToProject = { projectId ->
                    navigator.navigate(AppDestination.ProjectDetail(projectId.value))
                },
                onNavigateToTask = { taskId ->
                    navigator.navigate(AppDestination.TaskDetail(taskId.value))
                },
            )
        }
        composable<AppDestination.TaskEditor> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.TaskEditor>()
            TaskEditorScreen(
                initialDueDate = route.initialDueDate?.let { kotlinx.datetime.LocalDate.parse(it) },
                taskId = route.taskId,
                onBack = navigator::popBackStack,
            )
        }
        composable<AppDestination.NoteView> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.NoteView>()
            NotePreviewScreen(
                noteId = route.noteId,
                onBack = navigator::popBackStack,
                onEdit = { id -> navigator.navigate(AppDestination.NoteEditor(id)) },
                onNavigateToNote = { id -> navigator.navigate(AppDestination.NoteView(id)) },
                onNavigateToTask = { id -> navigator.navigate(AppDestination.TaskDetail(id)) },
            )
        }
        composable<AppDestination.NoteEditor> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.NoteEditor>()
            NoteEditorScreen(
                noteId = route.noteId,
                onBack = navigator::popBackStack,
                onNavigateToNote = { id -> navigator.navigate(AppDestination.NoteView(id)) },
                onNavigateToTask = { id -> navigator.navigate(AppDestination.TaskDetail(id)) },
            )
        }
        composable<AppDestination.ProjectEditor> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.ProjectEditor>()
            ProjectEditorScreen(
                projectId = route.projectId?.let { ProjectId.fromString(it) },
                onBack = navigator::popBackStack,
            )
        }
        composable<AppDestination.ProjectDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.ProjectDetail>()
            ProjectDetailScreen(
                projectId = ProjectId.fromString(route.projectId),
                onBack = navigator::popBackStack,
                onNavigateToTasks = { projectId ->
                    navigator.navigate(AppDestination.TasksByProject(projectId.value))
                },
                onNavigateToTask = { taskId ->
                    navigator.navigate(AppDestination.TaskDetail(taskId.value))
                },
            )
        }
        composable<AppDestination.TasksByProject> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.TasksByProject>()
            TasksByProjectScreen(
                projectId = ProjectId.fromString(route.projectId),
                onBack = navigator::popBackStack,
                onNavigateToTask = { taskId ->
                    navigator.navigate(AppDestination.TaskDetail(taskId.value))
                },
            )
        }
    }
}

// ─── Local route composables ────────────────────────────────────────────────
//
// Each tab owns its own internal backstack (selected detail / editor screen)
// via `rememberSaveable` so it survives config changes. State lives locally —
// no global mutable state, no leaking between tabs.

@Composable
private fun TasksRoute(entry: TasksScreenEntry, navigator: AppNavigator) {
    TasksScreen(
        viewModel = koinViewModel(),
        entry = entry,
        onNavigateToTask = { id -> navigator.navigate(AppDestination.TaskDetail(id)) },
        onNavigateToCreateTask = {
            navigator.navigate(AppDestination.TaskEditor(entry.toInitialDueDateString()))
        },
    )
}

@Composable
private fun NotesRoute(navigator: AppNavigator) {
    NotesScreen(
        viewModel = koinViewModel(),
        onNavigateToNote = { id -> navigator.navigate(AppDestination.NoteView(id)) },
        onNavigateToCreateNote = { navigator.navigate(AppDestination.NoteEditor()) },
    )
}

/** Translate a tab entry into the suggested initial due date (string for serializable Route). */
private fun TasksScreenEntry.toInitialDueDateString(): String? = when (this) {
    TasksScreenEntry.FromToday -> todayInSystemZone().toString()
    TasksScreenEntry.FromInbox -> null
}
