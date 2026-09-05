package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.FieldMode
import com.singularity.todo.core.ui.components.draftOr
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.ui.components.ProjectPickerSheet
import com.singularity.todo.feature.projects.ProjectId
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

    var showProjectPicker by remember { mutableStateOf(false) }

    when (val s = state) {
        TaskDetailUiState.Loading -> LoadingIndicator()
        is TaskDetailUiState.Error -> Text("Error: ${s.message}", modifier = Modifier.padding(16.dp))
        is TaskDetailUiState.Loaded -> TaskDetailContent(
            ui = s.ui,
            onSaveField = { field, draft -> viewModel.saveField(s.ui.task, field, draft) },
            onEditProject = { showProjectPicker = true },
            onToggleChecklist = viewModel::toggleChecklistItem,
            onBack = onBack,
        )
    }

    if (showProjectPicker) {
        val loaded = state as? TaskDetailUiState.Loaded
        ProjectPickerSheet(
            onProjectSelected = { project ->
                loaded?.let { viewModel.saveProject(it.ui.task, project?.id) }
            },
            onDismiss = { showProjectPicker = false },
        )
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag("task_detail_notification_host"),
    )
}

private fun TaskDetailUiEvent.toNotification(): Notification = when (this) {
    is TaskDetailUiEvent.Saved -> Notification.Text(title = "Saved", text = message)
    is TaskDetailUiEvent.Error -> Notification.Error(message)
    TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailContent(
    ui: TaskDetailUi,
    onSaveField: (TaskDetailField, String) -> Unit,
    onEditProject: () -> Unit,
    onToggleChecklist: (com.singularity.todo.feature.checklist.ChecklistItem) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.task.title.ifBlank { "Task" },
                        textDecoration = if (ui.task.isCompleted) TextDecoration.LineThrough else null,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditableTextRow(
                label = "Title",
                value = ui.task.title,
                mode = ui.titleField,
                singleLine = true,
                onSave = { onSaveField(TaskDetailField.Title, it) },
            )
            EditableTextRow(
                label = "Description",
                value = ui.task.description ?: "",
                mode = ui.descriptionField,
                singleLine = false,
                onSave = { onSaveField(TaskDetailField.Description, it) },
            )
            EditableTextRow(
                label = "Due date",
                value = ui.task.dueDate?.toString() ?: "",
                mode = ui.dueDateField,
                singleLine = true,
                onSave = { onSaveField(TaskDetailField.DueDate, it) },
            )
            EditableTextRow(
                label = "Due time",
                value = ui.task.dueTime ?: "",
                mode = ui.dueTimeField,
                singleLine = true,
                onSave = { onSaveField(TaskDetailField.DueTime, it) },
            )
            EditableTextRow(
                label = "Priority",
                value = ui.task.priority.name,
                mode = ui.priorityField,
                singleLine = true,
                onSave = { onSaveField(TaskDetailField.Priority, it) },
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Project", style = MaterialTheme.typography.labelMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(ui.project?.name ?: "(no project)")
                Button(onClick = onEditProject) { Text("Edit") }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Tags", style = MaterialTheme.typography.labelMedium)
            }
            if (ui.tags.isEmpty()) {
                Text("(none)", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ui.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag.name) })
                    }
                }
            }

            Text("Checklist (${ui.checklist.size})", style = MaterialTheme.typography.labelMedium)
            if (ui.checklist.isEmpty()) {
                Text("(none)", style = MaterialTheme.typography.bodySmall)
            } else {
                ui.checklist.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.isCompleted,
                            onCheckedChange = { onToggleChecklist(item) },
                        )
                        Text(
                            item.title,
                            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }

            Text("Reminders (${ui.reminders.size})", style = MaterialTheme.typography.labelMedium)
            if (ui.reminders.isEmpty()) {
                Text("(none)", style = MaterialTheme.typography.bodySmall)
            } else {
                ui.reminders.forEach { r ->
                    Text("• ${r.type.name} — fireAt=${r.fireAt}")
                }
            }

            Text("Attachments (${ui.attachments.size})", style = MaterialTheme.typography.labelMedium)
            if (ui.attachments.isEmpty()) {
                Text("(none)", style = MaterialTheme.typography.bodySmall)
            } else {
                ui.attachments.forEach { a ->
                    Text("• ${a.title.ifBlank { a.displayTitle }}")
                }
            }

            if (ui.task.isPinned) {
                Text("📌 Pinned", style = MaterialTheme.typography.labelSmall)
            }
            if (ui.task.archivedAt != null) {
                Text("🗑 Archived", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EditableTextRow(
    label: String,
    value: String,
    mode: FieldMode,
    singleLine: Boolean,
    onSave: (String) -> Unit,
) {
    var draft by remember(mode, value) { mutableStateOf(mode.draftOr(value)) }
    when (mode) {
        FieldMode.View -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value.ifBlank { "—" })
            }
            Button(onClick = { draft = value }) { Text("Edit") }
        }
        is FieldMode.Edit -> Column {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onSave(draft) }) { Text("Save") }
            }
        }
    }
}
