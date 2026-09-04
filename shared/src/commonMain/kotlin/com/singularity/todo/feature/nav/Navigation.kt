package com.singularity.todo.feature.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.notes.NotesScreen
import com.singularity.todo.feature.notes.NoteEditorScreen
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.projects.ProjectEditorScreen
import com.singularity.todo.feature.projects.ProjectsScreen
import com.singularity.todo.feature.search.SearchScreen
import com.singularity.todo.feature.settings.SettingsScreen
import com.singularity.todo.feature.statistics.StatisticsScreen
import com.singularity.todo.feature.tasks.TaskEditorScreen
import com.singularity.todo.feature.tasks.TasksScreen
import com.singularity.todo.feature.tasks.TasksScreenEntry
import com.singularity.todo.core.platform.isDesktop
import com.singularity.todo.core.platform.todayInSystemZone
import org.koin.compose.koinInject

@Composable
fun HomeTab() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val drawerStyle: DrawerStyle = remember { drawerStyleForPlatform() }

    AppShell(
        current = NavDestination.at(selectedIndex),
        drawerStyle = drawerStyle,
        onSelect = { dest -> selectedIndex = dest.ordinal },
    ) { modifier ->
        Box(modifier = modifier) {
            when (selectedIndex) {
                0 -> TasksSection(entry = TasksScreenEntry.FromToday)
                1 -> NotesSection()
                2 -> ProjectsSection()
                3 -> PomodoroScreen(timer = koinInject(), onBack = { selectedIndex = 0 })
                4 -> StatisticsScreen()
                5 -> ChatScreen()
                6 -> SearchScreen()
                7 -> ArchiveScreen()
                8 -> SettingsScreen()
            }
        }
    }
}

private fun drawerStyleForPlatform(): DrawerStyle =
    if (isDesktop) DrawerStyle.Permanent else DrawerStyle.Modal

@Composable
private fun TasksSection(entry: TasksScreenEntry) {
    var isCreatingTask by remember { mutableStateOf(false) }

    if (isCreatingTask) {
        val initialDueDate: kotlinx.datetime.LocalDate? = when (entry) {
            TasksScreenEntry.FromToday -> todayInSystemZone()
            TasksScreenEntry.FromInbox -> null
        }
        TaskEditorScreen(
            initialDueDate = initialDueDate,
            onBack = { isCreatingTask = false },
        )
    } else {
        TasksScreen(
            onNavigateToTask = { /* TODO: task detail screen */ },
            onNavigateToCreateTask = { isCreatingTask = true },
        )
    }
}

@Composable
private fun NotesSection() {
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var isCreatingNote by remember { mutableStateOf(false) }

    if (editingNoteId != null || isCreatingNote) {
        NoteEditorScreen(
            noteId = editingNoteId,
            onBack = {
                editingNoteId = null
                isCreatingNote = false
            }
        )
    } else {
        NotesScreen(
            onNavigateToNote = { id -> editingNoteId = id },
            onNavigateToCreateNote = { isCreatingNote = true }
        )
    }
}

@Composable
private fun ProjectsSection() {
    var isCreatingProject by remember { mutableStateOf(false) }

    if (isCreatingProject) {
        ProjectEditorScreen(
            onBack = { isCreatingProject = false },
        )
    } else {
        ProjectsScreen(
            onNavigateToProject = { /* TODO: project detail screen */ },
            onNavigateToCreateProject = { isCreatingProject = true },
        )
    }
}
