package com.singularity.todo.feature.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(viewModel: ArchiveViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val refreshing = state.let { it is ArchiveUiState.Content && it.refreshing }
            Button(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    androidx.compose.material3.Text("Archive completed tasks (tap to archive all)")
                }
            }
            when (val s = state) {
                ArchiveUiState.Loading -> LoadingIndicator()
                is ArchiveUiState.Error -> EmptyState(title = "Error", subtitle = s.message)
                is ArchiveUiState.Content -> {
                    if (s.tasks.isEmpty()) {
                        EmptyState(
                            title = "Archive is empty",
                            subtitle = "Tap the button above to archive completed tasks",
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(s.tasks, key = { it.id.value }) { task ->
                                TaskCard(
                                    task = task,
                                    onClick = { navigator.navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(task.id.value))) },
                                    actions = TaskCardActions.Empty,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag("archive_notification_host"),
    )
}

private fun ArchiveUiEvent.toNotification(): Notification = when (this) {
    is ArchiveUiEvent.Archived -> Notification.Text(title = "Archived", text = message)
    is ArchiveUiEvent.Error -> Notification.Error(message)
}

// ===== Preview =====

@Composable
private fun ArchiveContentPreview(state: ArchiveUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val refreshing = state.let { it is ArchiveUiState.Content && it.refreshing }
            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    androidx.compose.material3.Text("Archive completed tasks (tap to archive all)")
                }
            }
            when (state) {
                ArchiveUiState.Loading -> LoadingIndicator()
                is ArchiveUiState.Error -> EmptyState(title = "Error", subtitle = state.message)
                is ArchiveUiState.Content -> {
                    if (state.tasks.isEmpty()) {
                        EmptyState(
                            title = "Archive is empty",
                            subtitle = "Tap the button above to archive completed tasks",
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.tasks, key = { it.id.value }) { task ->
                                TaskCard(
                                    task = task,
                                    onClick = {},
                                    actions = TaskCardActions.Empty,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ArchiveScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    ArchiveContentPreview(
        ArchiveUiState.Content(
            tasks = listOf(
                PreviewSamples.task("t1", "Completed task 1", completed = true),
                PreviewSamples.task("t2", "Completed task 2", completed = true),
                PreviewSamples.task("t3", "Another completed task", completed = true),
            ),
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ArchiveScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    ArchiveContentPreview(ArchiveUiState.Content(tasks = emptyList()))
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ArchiveScreenLoadingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    ArchiveContentPreview(ArchiveUiState.Loading)
}
