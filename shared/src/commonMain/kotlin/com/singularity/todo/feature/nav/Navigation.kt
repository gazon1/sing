package com.singularity.todo.feature.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import com.singularity.todo.feature.tasks.TasksScreen
import com.singularity.todo.feature.notes.NotesScreen
import com.singularity.todo.feature.notes.NoteEditorScreen
import com.singularity.todo.feature.projects.ProjectsScreen
import com.singularity.todo.feature.search.SearchScreen
import com.singularity.todo.feature.settings.SettingsScreen

sealed class BottomNavItem(
    val title: String,
    val icon: ImageVector
) {
    data object Tasks : BottomNavItem("Tasks", Icons.Filled.Check)
    data object Notes : BottomNavItem("Notes", Icons.Filled.Create)
    data object Projects : BottomNavItem("Projects", Icons.Filled.Home)
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
                0 -> TasksScreen(onNavigateToTask = { }, onNavigateToCreateTask = { })
                1 -> NotesSection()
                2 -> ProjectsScreen(onNavigateToProject = { }, onNavigateToCreateProject = { })
                3 -> SearchScreen()
                4 -> SettingsScreen()
            }
        }
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
