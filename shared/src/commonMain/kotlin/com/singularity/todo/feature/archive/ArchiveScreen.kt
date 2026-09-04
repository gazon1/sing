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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.tasks.components.TaskCard
import com.singularity.todo.feature.tasks.components.TaskCardActions
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(viewModel: ArchiveViewModel = koinInject()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }
    CollectEvents(viewModel.events) { event ->
        when (event) {
            is UiEvent.ShowDialog -> dialogText = event.text
            is UiEvent.ShowError -> dialogText = event.message
            UiEvent.NavigateBack -> Unit
        }
    }

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
                ArchiveUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }
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

    ResultDialog(title = "Archive", text = dialogText, onDismiss = { dialogText = null })
}
