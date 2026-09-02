package com.singularity.todo.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onNavigateToTask: (String) -> Unit,
    onNavigateToCreateTask: () -> Unit
) {
    val viewModel: TasksViewModel = koinInject()
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = { /* navigate to search */ }) {
                        Icon(Icons.Filled.Add, "Search")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateTask) {
                Icon(Icons.Filled.Add, "Add Task")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FilterChipsRow(
                selected = filter,
                onSelect = viewModel::setFilter
            )

            when (val s = state) {
                is TasksUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is TasksUiState.Empty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tasks for ${s.filter}")
                }
                is TasksUiState.Content -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(s.tasks, key = { it.id.value }) { task ->
                        TaskCard(
                            task = task,
                            onClick = { onNavigateToTask(task.id.value) },
                            onToggle = { viewModel.toggle(task.id) },
                            onDelete = { viewModel.delete(task.id) }
                        )
                    }
                }
                is TasksUiState.Error -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${s.message}")
                }
            }
        }
    }
}

@Composable
fun FilterChipsRow(
    selected: TaskFilter,
    onSelect: (TaskFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected is TaskFilter.Today,
            onClick = { onSelect(TaskFilter.Today) },
            label = { Text("Today") }
        )
        FilterChip(
            selected = selected is TaskFilter.Upcoming,
            onClick = { onSelect(TaskFilter.Upcoming) },
            label = { Text("Upcoming") }
        )
        FilterChip(
            selected = selected is TaskFilter.Someday,
            onClick = { onSelect(TaskFilter.Someday) },
            label = { Text("Someday") }
        )
        FilterChip(
            selected = selected is TaskFilter.Inbox,
            onClick = { onSelect(TaskFilter.Inbox) },
            label = { Text("Inbox") }
        )
    }
}

@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isPinned)
                androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                androidx.compose.material3.MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (task.isCompleted) Icons.Filled.Check else Icons.Filled.Star,
                    contentDescription = "Toggle complete",
                    tint = if (task.isCompleted)
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                task.dueDate?.let { date ->
                    Text(
                        text = date.toString(),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PriorityChip(priority = task.priority)

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun PriorityChip(priority: TaskPriority) {
    if (priority == TaskPriority.None) return
    
    val color = when (priority) {
        TaskPriority.Low -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        TaskPriority.Medium -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        TaskPriority.High -> androidx.compose.ui.graphics.Color(0xFFF44336)
        TaskPriority.Urgent -> androidx.compose.ui.graphics.Color(0xFFE91E63)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    
    Box(
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            text = priority.name,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
