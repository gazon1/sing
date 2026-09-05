package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.files.toFilePickerResult
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ProjectPickerSheet
import com.singularity.todo.core.ui.components.TagPickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.feature.reminders.ReminderPicker
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tasks.components.TaskEditorContent
import com.singularity.todo.feature.tasks.components.TaskEditorDiscardDialog
import com.singularity.todo.feature.tasks.components.TaskEditorPrioritySheet
import com.singularity.todo.feature.tasks.components.TaskEditorSheetHost
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.koin.compose.koinInject

/**
 * Sealed hierarchy for the currently open bottom sheet.
 * Lives in the Composable (not VM) because sheet visibility is transient UI state.
 */
private sealed interface EditorSheet {
    data object None : EditorSheet
    data object Priority : EditorSheet
    data object Project : EditorSheet
    data object Date : EditorSheet
    data object Time : EditorSheet
    data object Reminder : EditorSheet
    data object Tags : EditorSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    initialDueDate: kotlinx.datetime.LocalDate? = null,
    taskId: String? = null,
    onBack: () -> Unit,
) {
    val vm: TaskEditorViewModel = koinInject()
    val state by vm.uiState.collectAsStateWithLifecycle()

    // Sheet routing — local UI state, NOT in VM
    var currentSheet by remember { mutableStateOf<EditorSheet>(EditorSheet.None) }
    // Dirty-confirmation dialog
    var showDiscardDialog by remember { mutableStateOf(false) }
    // Overflow menu
    var showActionsMenu by remember { mutableStateOf(false) }

    // Load task if editing
    LaunchedEffect(taskId) {
        if (taskId != null) {
            vm.onIntent(TaskEditorIntent.LoadTask(TaskId.fromString(taskId)))
        }
    }

    val filePickerLauncher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file != null) {
            val result = file.toFilePickerResult()
            vm.onIntent(TaskEditorIntent.AddAttachment(result.path, result.name, result.mimeType))
        }
    }

    val screenTitle = when (state.mode) {
        TaskEditorMode.New -> "New Task"
        is TaskEditorMode.Edit -> "Edit Task"
    }

    val canSave = state.title.isNotBlank() && !state.saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.dirty) {
                                showDiscardDialog = true
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = { vm.onIntent(TaskEditorIntent.Save) },
                            enabled = canSave,
                            modifier = Modifier.testTag(TestTags.TASK_EDITOR_SAVE),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                        Box {
                            IconButton(onClick = { showActionsMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showActionsMenu,
                                onDismissRequest = { showActionsMenu = false },
                            ) {
                                if (state.dirty) {
                                    DropdownMenuItem(
                                        text = { Text("Discard changes") },
                                        onClick = {
                                            showActionsMenu = false
                                            showDiscardDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        TaskEditorContent(
            state = state,
            onTitleChange = { vm.onIntent(TaskEditorIntent.TitleChanged(it)) },
            onDescriptionChange = { vm.onIntent(TaskEditorIntent.DescriptionChanged(it)) },
            onPriorityClick = { currentSheet = EditorSheet.Priority },
            onDateClick = { currentSheet = EditorSheet.Date },
            onTimeClick = { currentSheet = EditorSheet.Time },
            onDatePreset = { date -> vm.onIntent(TaskEditorIntent.DueDateChanged(date)) },
            onClearDate = { vm.onIntent(TaskEditorIntent.ClearDueDate) },
            onClearTime = { vm.onIntent(TaskEditorIntent.ClearDueTime) },
            onProjectClick = { currentSheet = EditorSheet.Project },
            onTagsClick = { currentSheet = EditorSheet.Tags },
            onClearProject = { vm.onIntent(TaskEditorIntent.ProjectChanged(null)) },
            onNewChecklistItemChange = { vm.onIntent(TaskEditorIntent.NewChecklistItemChanged(it)) },
            onAddChecklistItem = { vm.onIntent(TaskEditorIntent.AddChecklistItem) },
            onToggleChecklistItem = { vm.onIntent(TaskEditorIntent.ToggleChecklistItem(it)) },
            onDeleteChecklistItem = { vm.onIntent(TaskEditorIntent.DeleteChecklistItem(it)) },
            onReminderClick = { currentSheet = EditorSheet.Reminder },
            onAttachmentClick = { filePickerLauncher.launch() },
            onRemoveAttachment = { path -> vm.onIntent(TaskEditorIntent.RemovePendingAttachment(path)) },
            modifier = Modifier.padding(padding),
        )
    }

    // ─── Bottom Sheets ────────────────────────────────────────────────────────

    when (currentSheet) {
        EditorSheet.None -> {}

        EditorSheet.Priority -> {
            TaskEditorSheetHost(
                title = "Priority",
                onClose = { currentSheet = EditorSheet.None },
            ) {
                TaskEditorPrioritySheet(
                    selected = state.priority,
                    onSelect = { priority ->
                        vm.onIntent(TaskEditorIntent.PriorityChanged(priority))
                        currentSheet = EditorSheet.None
                    },
                )
            }
        }

        EditorSheet.Date -> {
            DatePickerSheet(
                initialDate = state.dueDate,
                onDateSelected = { date ->
                    vm.onIntent(TaskEditorIntent.DueDateChanged(date))
                    currentSheet = EditorSheet.None
                },
                onDismiss = { currentSheet = EditorSheet.None },
            )
        }

        EditorSheet.Time -> {
            TimePickerSheet(
                initialTime = state.dueTime,
                onTimeSelected = { time ->
                    vm.onIntent(TaskEditorIntent.DueTimeChanged(time))
                    currentSheet = EditorSheet.None
                },
                onDismiss = { currentSheet = EditorSheet.None },
            )
        }

        EditorSheet.Project -> {
            ProjectPickerSheet(
                onProjectSelected = { project ->
                    vm.onIntent(TaskEditorIntent.ProjectChanged(project?.id?.value))
                    currentSheet = EditorSheet.None
                },
                onDismiss = { currentSheet = EditorSheet.None },
            )
        }

        EditorSheet.Tags -> {
            TagPickerSheet(
                selectedTagIds = state.tagIds.toSet(),
                onTagsSelected = { tags ->
                    vm.onIntent(TaskEditorIntent.TagsChanged(tags.toList()))
                    currentSheet = EditorSheet.None
                },
                onDismiss = { currentSheet = EditorSheet.None },
            )
        }

        EditorSheet.Reminder -> {
            TaskEditorSheetHost(
                title = "Remind me",
                onClose = { currentSheet = EditorSheet.None },
            ) {
                ReminderPicker(
                    selected = state.reminderOffset ?: ReminderOffset.AT_DUE,
                    onSelect = { offset ->
                        vm.onIntent(TaskEditorIntent.ReminderOffsetChanged(offset))
                        currentSheet = EditorSheet.None
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    // ─── Discard Confirmation ────────────────────────────────────────────────

    if (showDiscardDialog) {
        TaskEditorDiscardDialog(
            onDiscard = {
                showDiscardDialog = false
                if (state.mode is TaskEditorMode.Edit) {
                    vm.onIntent(TaskEditorIntent.DiscardChanges)
                } else {
                    onBack()
                }
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    // ─── Event Handling ─────────────────────────────────────────────────────

    NotificationHost(
        events = vm.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag(TestTags.TASK_EDITOR_NOTIFICATION_HOST),
    )
}

private fun TaskEditorUiEvent.toNotification(): Notification = when (this) {
    is TaskEditorUiEvent.Error -> Notification.Error(message)
    TaskEditorUiEvent.NavigateBack -> Notification.NavigateBack
}
