package com.singularity.todo.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.List
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.ProjectPickerSheet
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.TagPickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.core.ui.components.UiEvent
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    initialDueDate: kotlinx.datetime.LocalDate? = null,
    onBack: () -> Unit,
) {
    val vm: TaskEditorViewModel = koinInject { parametersOf(initialDueDate) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }

    CollectEvents(vm.events) { event ->
        when (event) {
            is UiEvent.ShowDialog -> dialogText = event.text
            is UiEvent.ShowError -> dialogText = event.message
            UiEvent.NavigateBack -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.onIntent(TaskEditorIntent.Save) },
                        enabled = state.title.isNotBlank() && !state.saving,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        TaskEditorBody(
            state = state,
            onTitleChange = { vm.onIntent(TaskEditorIntent.TitleChanged(it)) },
            onDescriptionChange = { vm.onIntent(TaskEditorIntent.DescriptionChanged(it)) },
            onDueDateClick = { showDatePicker = true },
            onDueTimeClick = { showTimePicker = true },
            onProjectClick = { showProjectPicker = true },
            onTagsClick = { showTagPicker = true },
            onNewChecklistItemChange = { vm.onIntent(TaskEditorIntent.NewChecklistItemChanged(it)) },
            onAddChecklistItem = { vm.onIntent(TaskEditorIntent.AddChecklistItem) },
            onToggleChecklistItem = { vm.onIntent(TaskEditorIntent.ToggleChecklistItem(it)) },
            onDeleteChecklistItem = { vm.onIntent(TaskEditorIntent.DeleteChecklistItem(it)) },
            onSave = { vm.onIntent(TaskEditorIntent.Save) },
            modifier = Modifier.padding(padding),
        )
    }

    if (showDatePicker) {
        DatePickerSheet(
            initialDate = state.dueDate,
            onDateSelected = { vm.onIntent(TaskEditorIntent.DueDateChanged(it)) },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        TimePickerSheet(
            initialTime = state.dueTime,
            onTimeSelected = { vm.onIntent(TaskEditorIntent.DueTimeChanged(it)) },
            onDismiss = { showTimePicker = false },
        )
    }

    if (showProjectPicker) {
        ProjectPickerSheet(
            onProjectSelected = { project ->
                vm.onIntent(TaskEditorIntent.ProjectChanged(project?.id?.value))
            },
            onDismiss = { showProjectPicker = false },
        )
    }

    if (showTagPicker) {
        TagPickerSheet(
            selectedTagIds = state.tagIds.toSet(),
            onTagsSelected = { vm.onIntent(TaskEditorIntent.TagsChanged(it.toList())) },
            onDismiss = { showTagPicker = false },
        )
    }

    ResultDialog(title = "Error", text = dialogText, onDismiss = { dialogText = null })
}

@Composable
private fun TaskEditorBody(
    state: TaskEditorUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDueDateClick: () -> Unit,
    onDueTimeClick: () -> Unit,
    onProjectClick: () -> Unit,
    onTagsClick: () -> Unit,
    onNewChecklistItemChange: (String) -> Unit,
    onAddChecklistItem: () -> Unit,
    onToggleChecklistItem: (String) -> Unit,
    onDeleteChecklistItem: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { msg ->
                { Text(msg, color = MaterialTheme.colorScheme.error) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        // Date & Time row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onDueDateClick)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CalendarToday,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.dueDate?.toString() ?: "Set date",
                    color = if (state.dueDate != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onDueTimeClick)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.dueTime?.toString() ?: "Set time",
                    color = if (state.dueTime != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Project & Tags row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onProjectClick)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.projectId != null) "Project" else "No project",
                    color = if (state.projectId != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTagsClick)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.tagIds.isNotEmpty()) "${state.tagIds.size} tag(s)" else "Add tags",
                    color = if (state.tagIds.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Checklist section
        Text(
            text = "Checklist",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Add checklist item
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.newChecklistItem,
                onValueChange = onNewChecklistItemChange,
                placeholder = { Text("Add checklist item…") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddChecklistItem() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onAddChecklistItem) {
                Icon(Icons.Filled.List, contentDescription = "Add item")
            }
        }

        // Checklist items
        state.checklistItems.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.isCompleted,
                    onCheckedChange = { onToggleChecklistItem(item.id) },
                )
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onDeleteChecklistItem(item.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSave,
            enabled = state.title.isNotBlank() && !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.saving) "Saving…" else "Create task")
        }
    }
}
