package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ProjectPickerSheet
import com.singularity.todo.core.ui.components.TagPickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.core.ui.components.formatDueChip
import com.singularity.todo.core.ui.components.priorityColorByIndex
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.attachments.AttachmentSheet
import com.singularity.todo.feature.reminders.ReminderPicker
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tasks.components.TaskDetailActions
import com.singularity.todo.feature.tasks.components.TaskEditorPrioritySheet
import com.singularity.todo.feature.tasks.sections.TaskBottomActionBar
import com.singularity.todo.feature.tasks.sections.TaskChecklistSection
import com.singularity.todo.feature.tasks.sections.TaskHeroSection
import com.singularity.todo.feature.tasks.sections.TaskMetaChipsRow
import com.singularity.todo.feature.tasks.sections.TagsRow
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import com.singularity.todo.core.files.toFilePickerResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.LocalTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// ─── Public screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: TaskId,
    onBack: () -> Unit,
    onNavigateToProject: (ProjectId) -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(),
    attachmentsVm: AttachmentsViewModel = koinViewModel(),
) {
    LaunchedEffect(taskId) {
        viewModel.start(taskId)
        attachmentsVm.watchAttachments(taskId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attachmentsState by attachmentsVm.state.collectAsStateWithLifecycle()

    var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val filePickerLauncher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        file?.let {
            val result = it.toFilePickerResult()
            attachmentsVm.saveFileAttachment(taskId, result.path, result.mimeType)
        }
    }

    // Map VM events to the active sheet state.
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            val sheet = event.toActiveSheet()
            if (sheet != null) {
                activeSheet = sheet
            }
        }
    }

    val loaded = (state as? TaskDetailUiState.Loaded)?.ui

    when (val s = state) {
        TaskDetailUiState.Loading -> LoadingIndicator()
        is TaskDetailUiState.Error -> Text("Error: ${s.message}", modifier = Modifier.padding(16.dp))
        is TaskDetailUiState.Loaded -> {
            val ui = s.ui

            // Pack all callbacks into a single TaskDetailActions value class.
            val actions = remember(ui) {
                TaskDetailActions { action ->
                    when (action) {
                        // Hero
                        TaskDetailActions.Action.ToggleComplete ->
                            viewModel.setCompleted(ui.task, !ui.task.isCompleted)
                        is TaskDetailActions.Action.TitleChange ->
                            viewModel.onTitleChange(action.title)
                        is TaskDetailActions.Action.DescriptionChange ->
                            viewModel.onDescriptionChange(action.description)

                        // Meta chips
                        TaskDetailActions.Action.OpenDatePicker -> viewModel.openDatePicker()
                        TaskDetailActions.Action.OpenTimePicker -> viewModel.openTimePicker()
                        TaskDetailActions.Action.OpenPriorityPicker -> viewModel.openPrioritySheet()
                        TaskDetailActions.Action.OpenProjectPicker -> viewModel.openProjectSheet()
                        is TaskDetailActions.Action.NavigateToProject -> onNavigateToProject(action.id)

                        // Tags
                        TaskDetailActions.Action.AddTag -> viewModel.openTagSheet()
                        is TaskDetailActions.Action.RemoveTag ->
                            viewModel.removeTag(ui.task, action.id)

                        // Checklist
                        is TaskDetailActions.Action.ToggleChecklistItem ->
                            viewModel.toggleChecklistItem(action.item)
                        is TaskDetailActions.Action.DeleteChecklistItem ->
                            viewModel.deleteChecklistItem(action.id)
                        is TaskDetailActions.Action.AddChecklistItem ->
                            viewModel.addChecklistItem(ui.task.id, action.title)

                        // Bottom bar
                        TaskDetailActions.Action.OpenReminderSheet -> viewModel.openReminderSheet()
                        TaskDetailActions.Action.OpenAttachmentSheet -> viewModel.openAttachmentSheet()
                        TaskDetailActions.Action.TogglePin ->
                            viewModel.setPinned(ui.task, !ui.task.isPinned)
                        TaskDetailActions.Action.OpenDeleteConfirm -> activeSheet = ActiveSheet.ConfirmDelete

                        // Dialog
                        TaskDetailActions.Action.OpenArchiveConfirm -> activeSheet = ActiveSheet.ConfirmArchive
                    }
                }
            }

            TaskDetailContent(
                ui = ui,
                actions = actions,
                onBack = onBack,
                showActionsMenu = showActionsMenu,
                onShowActionsMenuChange = { showActionsMenu = it },
            )
        }
    }

    // ─── Sheet overlays ─────────────────────────────────────────────────────────

    when (val sheet = activeSheet) {
        ActiveSheet.Date -> {
            DatePickerSheet(
                initialDate = loaded?.task?.dueDate,
                onDateSelected = { date -> loaded?.let { viewModel.setDueDate(it.task, date) } },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Time -> {
            val currentTime = loaded?.task?.dueTime?.let { time ->
                runCatching {
                    val parts = time.split(":")
                    LocalTime(parts[0].toInt(), parts[1].toInt())
                }.getOrNull()
            }
            TimePickerSheet(
                initialTime = currentTime,
                onTimeSelected = { time -> loaded?.let { viewModel.setDueTime(it.task, time?.toString()) } },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Priority -> {
            TaskEditorPrioritySheet(
                selected = loaded?.task?.priority ?: TaskPriority.None,
                onSelect = { priority ->
                    loaded?.let { viewModel.setPriority(it.task, priority) }
                    activeSheet = null
                },
            )
        }
        ActiveSheet.Project -> {
            ProjectPickerSheet(
                onProjectSelected = { project -> loaded?.let { viewModel.setProject(it.task, project?.id) } },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Tags -> {
            loaded?.let { ui ->
                TagPickerSheet(
                    selectedTagIds = ui.task.tags.map { it.value }.toSet(),
                    onTagsSelected = { selectedIds ->
                        val tagIds = selectedIds.map { TagId.fromString(it) }
                        viewModel.setTags(ui.task, tagIds)
                    },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.Reminder -> {
            loaded?.let { ui ->
                ReminderPickerSheetContent(
                    task = ui.task,
                    onReminderSet = { offset -> viewModel.setReminder(ui.task, offset) },
                    onReminderDeleted = { viewModel.deleteReminder(ui.task) },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.Attachment -> {
            loaded?.let { ui ->
                AttachmentSheet(
                    taskId = ui.task.id,
                    attachments = ui.attachments,
                    viewModel = attachmentsVm,
                    onPickFile = { filePickerLauncher.launch() },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.ConfirmDelete -> {
            ConfirmDeleteDialog(
                onConfirm = {
                    loaded?.let { viewModel.deleteTask(it.task) }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.ConfirmArchive -> {
            ConfirmArchiveDialog(
                onConfirm = {
                    loaded?.let { viewModel.archiveTask(it.task) }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        null -> {}
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier,
    )
}

// ─── Main content ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailContent(
    ui: TaskDetailUi,
    actions: TaskDetailActions,
    onBack: () -> Unit,
    showActionsMenu: Boolean,
    onShowActionsMenuChange: (Boolean) -> Unit,
) {
    val today = todayInSystemZone()
    val scrollState = rememberScrollState()

    // Pre-compute chip colours so section composables stay stateless.
    val dueChipModel = formatDueChip(ui.task.dueDate, ui.task.dueTime, today)
    val (dueDateBg, dueDateFg) = when (dueChipModel?.state) {
        com.singularity.todo.core.ui.components.DueVisualState.Overdue ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        com.singularity.todo.core.ui.components.DueVisualState.Today ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val priorityIconColor = priorityColorByIndex(ui.task.priority.ordinal)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { onShowActionsMenuChange(true) }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { onShowActionsMenuChange(false) },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                                onClick = {
                                    onShowActionsMenuChange(false)
                                    actions.onArchive()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            TaskBottomActionBar(
                remindersCount = ui.reminders.size,
                attachmentsCount = ui.attachments.size,
                isPinned = ui.task.isPinned,
                actions = actions,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TaskHeroSection(
                title = ui.task.title,
                description = ui.task.description,
                isCompleted = ui.task.isCompleted,
                actions = actions,
            )

            TaskMetaChipsRow(
                dueDateText = dueChipModel?.text,
                dueDateBg = dueDateBg,
                dueDateFg = dueDateFg,
                priority = ui.task.priority,
                priorityIconColor = priorityIconColor,
                project = ui.project,
                actions = actions,
            )

            if (ui.tags.isNotEmpty()) {
                TagsRow(
                    tags = ui.tags,
                    actions = actions,
                )
            }

            TaskChecklistSection(
                items = ui.checklist,
                actions = actions,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─── Tags row ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(
    tags: List<com.singularity.todo.feature.tags.Tag>,
    actions: TaskDetailActions,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { actions.onRemoveTag(tag.id) },
                label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove ${tag.name}",
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }
        SuggestionChip(
            onClick = actions::onAddTag,
            label = { Text("+ Add tag") },
            icon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            },
        )
    }
}

// ─── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun ConfirmArchiveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive task?") },
        text = { Text("This will move it to the Archive and remove it from your active lists.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Archive", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete task?") },
        text = { Text("This action cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ─── Mappers ─────────────────────────────────────────────────────────────────

private fun TaskDetailUiEvent.toNotification(): Notification = when (this) {
    is TaskDetailUiEvent.Saved -> Notification.Text(title = "Saved", text = message)
    is TaskDetailUiEvent.Error -> Notification.Error(message)
    TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
    else -> Notification.Dismiss
}
