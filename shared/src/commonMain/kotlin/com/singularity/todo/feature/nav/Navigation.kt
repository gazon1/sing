package com.singularity.todo.feature.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.feature.ai.chat.ChatScreen
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
import com.singularity.todo.core.platform.todayInSystemZone
import org.koin.compose.koinInject

sealed class BottomNavItem(
    val title: String,
    val icon: ImageVector
) {
    data object Tasks : BottomNavItem("Tasks", Icons.Filled.Check)
    data object Notes : BottomNavItem("Notes", Icons.Filled.Create)
    data object Projects : BottomNavItem("Projects", Icons.Filled.Home)
    data object Pomodoro : BottomNavItem("Timer", Icons.Filled.Timer)
    data object Statistics : BottomNavItem("Stats", Icons.Filled.BarChart)
    data object Chat : BottomNavItem("AI Chat", Icons.Filled.AutoAwesome)
    data object Search : BottomNavItem("Search", Icons.Filled.Search)
    data object Settings : BottomNavItem("Settings", Icons.Filled.Settings)
}

@Composable
fun HomeTab() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val items = listOf(
        BottomNavItem.Tasks,
        BottomNavItem.Notes,
        BottomNavItem.Projects,
        BottomNavItem.Pomodoro,
        BottomNavItem.Statistics,
        BottomNavItem.Chat,
        BottomNavItem.Search,
        BottomNavItem.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedIndex) {
                0 -> TasksSection(entry = TasksScreenEntry.FromToday)
                1 -> NotesSection()
                2 -> ProjectsSection()
                3 -> PomodoroScreen(timer = koinInject(), onBack = { selectedIndex = 0 })
                4 -> StatisticsScreen()
                5 -> ChatScreen()
                6 -> SearchScreen()
                7 -> SettingsScreen()
            }
        }
    }
}

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
