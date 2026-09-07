package com.singularity.todo.feature.tasks

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ProjectPickerSheet
import com.singularity.todo.core.ui.components.TagPickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.core.ui.components.formatDueChip
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.components.priorityColorByIndex
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.attachments.AttachmentSheet
import com.singularity.todo.feature.reminders.ReminderPicker
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tasks.components.TaskEditorPrioritySheet
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import com.singularity.todo.core.files.toFilePickerResult
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Callback type aliases for TaskDetailContent to keep signatures clean. */
private typealias SetPriority = (TaskPriority) -> Unit
private typealias SetProject = (ProjectId?) -> Unit
private typealias RemoveTag = (TagId) -> Unit
private typealias AddTags = (List<TagId>) -> Unit
private typealias ToggleChecklistItem = (ChecklistItem) -> Unit
private typealias DeleteChecklistItem = (ChecklistItemId) -> Unit
private typealias AddChecklistItem = (String) -> Unit
private typealias SetReminder = (Task, ReminderOffset) -> Unit
private typealias DeleteReminder = (Task) -> Unit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    taskId: TaskId,
    onBack: () -> Unit,
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

    when (val s = state) {
        TaskDetailUiState.Loading -> LoadingIndicator()
        is TaskDetailUiState.Error -> Text("Error: ${s.message}", modifier = Modifier.padding(16.dp))
        is TaskDetailUiState.Loaded -> TaskDetailContent(
            ui = s.ui,
            onTitleChange = viewModel::onTitleChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onToggleComplete = { viewModel.setCompleted(s.ui.task, !s.ui.task.isCompleted) },
            onOpenDatePicker = viewModel::openDatePicker,
            onOpenTimePicker = viewModel::openTimePicker,
            onOpenPrioritySheet = viewModel::openPrioritySheet,
            onOpenProjectSheet = viewModel::openProjectSheet,
            onOpenTagSheet = viewModel::openTagSheet,
            onOpenReminderSheet = viewModel::openReminderSheet,
            onOpenAttachmentSheet = viewModel::openAttachmentSheet,
            onTogglePin = { viewModel.setPinned(s.ui.task, !s.ui.task.isPinned) },
            onConfirmDelete = viewModel::confirmDelete,
            onDeleteTask = { viewModel.deleteTask(s.ui.task) },
            onSetDueDate = { viewModel.setDueDate(s.ui.task, it) },
            onSetDueTime = { viewModel.setDueTime(s.ui.task, it) },
            onSetPriority = { viewModel.setPriority(s.ui.task, it) },
            onSetProject = { viewModel.setProject(s.ui.task, it) },
            onRemoveTag = { viewModel.removeTag(s.ui.task, it) },
            onAddTags = { viewModel.setTags(s.ui.task, it) },
            onToggleChecklistItem = viewModel::toggleChecklistItem,
            onDeleteChecklistItem = viewModel::deleteChecklistItem,
            onAddChecklistItem = { viewModel.addChecklistItem(s.ui.task.id, it) },
            onSetReminder = { task, offset -> viewModel.setReminder(task, offset) },
            onDeleteReminder = { task -> viewModel.deleteReminder(task) },
            onDismissSheet = { activeSheet = null },
            onBack = onBack,
            showActionsMenu = showActionsMenu,
            onShowActionsMenuChange = { showActionsMenu = it },
            onArchive = viewModel::confirmArchive,
        )
    }

    // ─── Sheet / dialog overlays ───────────────────────────────────────────────

    val loaded = (state as? TaskDetailUiState.Loaded)?.ui

    when (val sheet = activeSheet) {
        ActiveSheet.Date -> {
            DatePickerSheet(
                initialDate = loaded?.task?.dueDate,
                onDateSelected = { date ->
                    loaded?.let { viewModel.setDueDate(it.task, date) }
                },
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
                onTimeSelected = { time ->
                    loaded?.let { viewModel.setDueTime(it.task, time?.toString()) }
                },
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
                onProjectSelected = { project ->
                    loaded?.let { viewModel.setProject(it.task, project?.id) }
                },
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

private fun TaskDetailUiEvent.toNotification(): Notification = when (this) {
    is TaskDetailUiEvent.Saved -> Notification.Text(title = "Saved", text = message)
    is TaskDetailUiEvent.Error -> Notification.Error(message)
    TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
    else -> Notification.Dismiss
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPickerSheetContent(
    task: Task,
    onReminderSet: (ReminderOffset) -> Unit,
    onReminderDeleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedOffset by remember { mutableStateOf(ReminderOffset.FIFTEEN_MIN) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Reminder",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            ReminderPicker(
                selected = selectedOffset,
                onSelect = { selectedOffset = it },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(
                    onClick = {
                        if (selectedOffset == ReminderOffset.AT_DUE) {
                            onReminderDeleted()
                        } else {
                            onReminderSet(selectedOffset)
                        }
                        onDismiss()
                    },
                ) {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailContent(
    ui: TaskDetailUi,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onToggleComplete: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onOpenTimePicker: () -> Unit,
    onOpenPrioritySheet: () -> Unit,
    onOpenProjectSheet: () -> Unit,
    onOpenTagSheet: () -> Unit,
    onOpenReminderSheet: () -> Unit,
    onOpenAttachmentSheet: () -> Unit,
    onTogglePin: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDeleteTask: () -> Unit,
    onSetDueDate: (LocalDate?) -> Unit,
    onSetDueTime: (String?) -> Unit,
    onSetPriority: SetPriority,
    onSetProject: SetProject,
    onRemoveTag: RemoveTag,
    onAddTags: AddTags,
    onToggleChecklistItem: ToggleChecklistItem,
    onDeleteChecklistItem: DeleteChecklistItem,
    onAddChecklistItem: AddChecklistItem,
    onSetReminder: SetReminder,
    onDeleteReminder: DeleteReminder,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
    showActionsMenu: Boolean = false,
    onShowActionsMenuChange: (Boolean) -> Unit = {},
    onArchive: () -> Unit = {},
) {
    val today = todayInSystemZone()
    val scrollState = rememberScrollState()

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
                                    onArchive()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar(
                remindersCount = ui.reminders.size,
                attachmentsCount = ui.attachments.size,
                isPinned = ui.task.isPinned,
                onRemind = onOpenReminderSheet,
                onAttach = onOpenAttachmentSheet,
                onPin = onTogglePin,
                onDelete = onConfirmDelete,
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
            // ── Hero section ─────────────────────────────────────────────────────
            TaskHeroSection(
                title = ui.task.title,
                description = ui.task.description,
                isCompleted = ui.task.isCompleted,
                onToggleComplete = onToggleComplete,
                onTitleChange = onTitleChange,
                onDescriptionChange = onDescriptionChange,
            )

            // ── Meta chips ────────────────────────────────────────────────────
            MetaChipsRow(
                dueChip = formatDueChip(ui.task.dueDate, ui.task.dueTime, today),
                isCompleted = ui.task.isCompleted,
                priority = ui.task.priority,
                projectName = ui.project?.name,
                onPickDate = onOpenDatePicker,
                onPickTime = onOpenTimePicker,
                onPickPriority = onOpenPrioritySheet,
                onPickProject = onOpenProjectSheet,
            )

            // ── Tags ─────────────────────────────────────────────────────────
            if (ui.tags.isNotEmpty() || true) {
                TagsRow(
                    tags = ui.tags,
                    onRemoveTag = onRemoveTag,
                    onAddTag = onOpenTagSheet,
                )
            }

            // ── Checklist ────────────────────────────────────────────────────
            ChecklistSection(
                items = ui.checklist,
                onToggle = onToggleChecklistItem,
                onDelete = onDeleteChecklistItem,
                onAdd = onAddChecklistItem,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─── Hero section ───────────────────────────────────────────────────────────────

@Composable
private fun TaskHeroSection(
    title: String,
    description: String?,
    isCompleted: Boolean,
    onToggleComplete: () -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = isCompleted,
            onCheckedChange = { onToggleComplete() },
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Title — inline editable
            InlineEditableText(
                value = title,
                placeholder = "Task title",
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                onValueChange = onTitleChange,
            )
            // Description — inline editable
            InlineEditableText(
                value = description ?: "",
                placeholder = "Add description...",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (description.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                textDecoration = null,
                onValueChange = onDescriptionChange,
            )
        }
    }
}

@Composable
private fun InlineEditableText(
    value: String,
    placeholder: String,
    style: TextStyle,
    textDecoration: TextDecoration?,
    onValueChange: (String) -> Unit,
) {
    Box {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style.copy(textDecoration = textDecoration),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Meta chips row ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChipsRow(
    dueChip: com.singularity.todo.core.ui.components.DueChipModel?,
    isCompleted: Boolean,
    priority: TaskPriority,
    projectName: String?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onPickPriority: () -> Unit,
    onPickProject: () -> Unit,
) {
    val chipColors = dueChip?.let { chip ->
        when (chip.state) {
            com.singularity.todo.core.ui.components.DueVisualState.Overdue ->
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            com.singularity.todo.core.ui.components.DueVisualState.Today ->
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            com.singularity.todo.core.ui.components.DueVisualState.Future ->
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Date + Time chip
        if (dueChip != null) {
            val (bg, fg) = chipColors ?: (MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(
                selected = true,
                onClick = onPickDate,
                label = { Text(dueChip.text, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    containerColor = bg,
                    labelColor = fg,
                    iconColor = fg,
                ),
            )
        } else {
            SuggestionChip(
                onClick = onPickDate,
                label = { Text("Set date", style = MaterialTheme.typography.labelMedium) },
                icon = {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }

        // Priority chip
        FilterChip(
            selected = true,
            onClick = onPickPriority,
            label = { Text(priority.name, style = MaterialTheme.typography.labelMedium) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (priority != TaskPriority.None) {
                        priorityColorByIndex(priority.ordinal)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        )

        // Project chip
        FilterChip(
            selected = projectName != null,
            onClick = onPickProject,
            label = {
                Text(
                    projectName ?: "Project",
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

// ─── Tags row ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(
    tags: List<com.singularity.todo.feature.tags.Tag>,
    onRemoveTag: (com.singularity.todo.feature.tags.TagId) -> Unit,
    onAddTag: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { onRemoveTag(tag.id) },
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
            onClick = onAddTag,
            label = { Text("+ Add tag") },
            icon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            },
        )
    }
}

// ─── Checklist section ────────────────────────────────────────────────────────

@Composable
private fun ChecklistSection(
    items: List<com.singularity.todo.feature.checklist.ChecklistItem>,
    onToggle: (com.singularity.todo.feature.checklist.ChecklistItem) -> Unit,
    onDelete: (com.singularity.todo.feature.checklist.ChecklistItemId) -> Unit,
    onAdd: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    if (items.isEmpty() && draft.isEmpty()) return

    val done = items.count { it.isCompleted }
    val total = items.size

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Header with progress
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Checklist",
                style = MaterialTheme.typography.titleSmall,
            )
            if (total > 0) {
                Text(
                    "$done/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Progress bar
        if (total > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { done.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Items
        items.forEach { item ->
            ChecklistItemRow(
                text = item.title,
                checked = item.isCompleted,
                onToggle = { onToggle(item) },
                onDelete = { onDelete(item.id) },
            )
        }

        // Add item inline
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Add item...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }
    }
}

// ─── Bottom action bar ────────────────────────────────────────────────────────

@Composable
private fun BottomActionBar(
    remindersCount: Int,
    attachmentsCount: Int,
    isPinned: Boolean,
    onRemind: () -> Unit,
    onAttach: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Remind
            IconButtonWithBadge(
                icon = Icons.Filled.Notifications,
                badgeCount = remindersCount,
                contentDescription = "Remind",
                onClick = onRemind,
            )
            // Attach
            IconButtonWithBadge(
                icon = Icons.Filled.AttachFile,
                badgeCount = attachmentsCount,
                contentDescription = "Attach",
                onClick = onAttach,
            )
            // Pin
            IconButton(onClick = onPin) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin",
                    tint = if (isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Delete
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IconButtonWithBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeCount: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
        if (badgeCount > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp),
            ) {
                Text("$badgeCount")
            }
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDetailContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TaskDetailContent(
        ui = TaskDetailUi(
            task = PreviewSamples.task(
                id = "t1",
                title = "Complete project proposal",
                priority = TaskPriority.High,
                completed = false,
            ),
            project = PreviewSamples.project(name = "Work"),
            tags = listOf(
                PreviewSamples.tag("tg1", "urgent", 0xFFF44336.toInt()),
                PreviewSamples.tag("tg2", "client", 0xFF2196F3.toInt()),
            ),
            checklist = listOf(
                PreviewSamples.checklistItem("Research phase", done = true),
                PreviewSamples.checklistItem("Draft outline", done = true),
                PreviewSamples.checklistItem("Final review", done = false),
            ),
        ),
        onTitleChange = {},
        onDescriptionChange = {},
        onToggleComplete = {},
        onOpenDatePicker = {},
        onOpenTimePicker = {},
        onOpenPrioritySheet = {},
        onOpenProjectSheet = {},
        onOpenTagSheet = {},
        onOpenReminderSheet = {},
        onOpenAttachmentSheet = {},
        onTogglePin = {},
        onConfirmDelete = {},
        onDeleteTask = {},
        onSetDueDate = {},
        onSetDueTime = {},
        onSetPriority = {},
        onSetProject = {},
        onRemoveTag = {},
        onAddTags = {},
        onToggleChecklistItem = {},
        onDeleteChecklistItem = {},
        onAddChecklistItem = {},
        onSetReminder = { _, _ -> },
        onDeleteReminder = { _ -> },
        onDismissSheet = {},
        onBack = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDetailContentDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    TaskDetailContent(
        ui = TaskDetailUi(
            task = PreviewSamples.task(
                id = "t2",
                title = "Buy groceries",
                priority = TaskPriority.Medium,
                completed = true,
            ),
        ),
        onTitleChange = {},
        onDescriptionChange = {},
        onToggleComplete = {},
        onOpenDatePicker = {},
        onOpenTimePicker = {},
        onOpenPrioritySheet = {},
        onOpenProjectSheet = {},
        onOpenTagSheet = {},
        onOpenReminderSheet = {},
        onOpenAttachmentSheet = {},
        onTogglePin = {},
        onConfirmDelete = {},
        onDeleteTask = {},
        onSetDueDate = {},
        onSetDueTime = {},
        onSetPriority = {},
        onSetProject = {},
        onRemoveTag = {},
        onAddTags = {},
        onToggleChecklistItem = {},
        onDeleteChecklistItem = {},
        onAddChecklistItem = {},
        onSetReminder = { _, _ -> },
        onDeleteReminder = { _ -> },
        onDismissSheet = {},
        onBack = {},
    )
}
