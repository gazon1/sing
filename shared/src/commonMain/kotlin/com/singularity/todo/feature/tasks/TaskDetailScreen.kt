package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.FieldMode
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: TaskId,
    onBack: () -> Unit,
    viewModel: TaskDetailViewModel = koinInject(),
) {
    LaunchedEffect(taskId) { viewModel.start(taskId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }
    CollectEvents(viewModel.events) { event ->
        when (event) {
            is UiEvent.ShowDialog -> dialogText = event.text
            is UiEvent.ShowError -> dialogText = event.message
            UiEvent.NavigateBack -> onBack()
        }
    }

    when (val s = state) {
        TaskDetailUiState.Loading -> LoadingIndicator()
        is TaskDetailUiState.Error -> Text("Error: ${s.message}", modifier = Modifier.padding(16.dp))
        is TaskDetailUiState.Loaded -> TaskDetailContent(
            state = s,
            onTitleEdit = { viewModel.saveField(s.task, TaskDetailField.Title, it) },
            onDescriptionEdit = { viewModel.saveField(s.task, TaskDetailField.Description, it) },
            onBack = onBack,
        )
    }

    ResultDialog(title = "Task", text = dialogText, onDismiss = { dialogText = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailContent(
    state: TaskDetailUiState.Loaded,
    onTitleEdit: (String) -> Unit,
    onDescriptionEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task", textDecoration = if (state.task.isCompleted) TextDecoration.LineThrough else null) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditableField(
                label = "Title",
                value = state.task.title,
                mode = state.titleField,
                onChangeToView = { /* field stays View after save */ },
                onEditDraftChanged = { /* updated via local state */ },
                onSave = onTitleEdit,
            )
            EditableField(
                label = "Description",
                value = state.task.description ?: "",
                mode = state.descriptionField,
                onChangeToView = { /* field stays View after save */ },
                onEditDraftChanged = { /* updated via local state */ },
                onSave = onDescriptionEdit,
            )
        }
    }
}

/**
 * Single inline-edit field. Tap to enter Edit mode; Save commits; Cancel reverts.
 * Local draft state avoids round-tripping through the VM for every keystroke.
 */
@Composable
private fun EditableField(
    label: String,
    value: String,
    mode: FieldMode,
    onChangeToView: () -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(mode, value) { mutableStateOf(mode.draftOr(value)) }
    when (mode) {
        FieldMode.View -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Text(value.ifBlank { "—" })
            }
            Button(onClick = { draft = value; onEditDraftChanged(value) /* not used for transition */ }) {
                Text("Edit")
            }
        }
        is FieldMode.Edit -> Column {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; onEditDraftChanged(it) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = label == "Title",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onSave(draft); onChangeToView() }) { Text("Save") }
            }
        }
    }
}

private fun FieldMode.draftOr(fallback: String): String =
    if (this is FieldMode.Edit) draft else fallback
