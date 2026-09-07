package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.feature.tasks.components.TaskEditorSheetHost
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalLinkPickerSheet(
    onNoteSelected: (noteId: String, title: String) -> Unit,
    onTaskSelected: (taskId: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val linkRepo: InternalLinkRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()

    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val tabs = listOf("Notes", "Tasks")

    fun search(q: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            isLoading = true
            if (q.isBlank()) {
                notes = emptyList()
                tasks = emptyList()
            } else {
                val userId = settingsRepo.userId.first()
                notes = linkRepo.searchNotes(UserId.fromString(userId), q)
                tasks = linkRepo.searchTasks(q)
            }
            isLoading = false
        }
    }

    TaskEditorSheetHost(
        title = "Link to Note or Task",
        onClose = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search field
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
                value = query,
                onValueChange = { q ->
                    query = q
                    search(q)
                },
                placeholder = { Text("Search by title...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            // Results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(320.dp),
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    selectedTab == 0 -> {
                        // Notes tab
                        if (notes.isEmpty() && query.isNotBlank()) {
                            Text(
                                "No notes found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                            )
                        } else {
                            LazyColumn {
                                items(notes, key = { it.id.value }) { note ->
                                    ListItem(
                                        text = note.title.ifBlank { "(Untitled)" },
                                        secondary = note.updatedAt.toString(),
                                        onClick = {
                                            onNoteSelected(note.id.value, note.title)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    selectedTab == 1 -> {
                        // Tasks tab
                        if (tasks.isEmpty() && query.isNotBlank()) {
                            Text(
                                "No tasks found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                            )
                        } else {
                            LazyColumn {
                                items(tasks, key = { it.id.value }) { task ->
                                    ListItem(
                                        text = task.title,
                                        secondary = task.updatedAt.toString(),
                                        onClick = {
                                            onTaskSelected(task.id.value, task.title)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListItem(
    text: String,
    secondary: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.ListItem(
        headlineContent = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
