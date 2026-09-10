package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.singularity.todo.core.ui.components.dueChipColors
import com.singularity.todo.core.ui.components.formatDueChip
import com.singularity.todo.core.ui.components.formatTimestampsRelative
import com.singularity.todo.core.ui.components.priorityColorByIndex
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.attachments.AttachmentSheet
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.ActiveSheet
import com.singularity.todo.feature.tasks.domain.model.TaskDetailIntent
import com.singularity.todo.feature.tasks.domain.model.TaskDetailUi
import com.singularity.todo.feature.tasks.domain.model.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.domain.model.TaskDetailUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskDetailActions
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorPrioritySheet
import com.singularity.todo.feature.tasks.presentation.sheet.ConfirmArchiveSheet
import com.singularity.todo.feature.tasks.presentation.sheet.ConfirmDeleteSheet
import com.singularity.todo.feature.tasks.presentation.sheet.KindSheet
import com.singularity.todo.feature.tasks.presentation.sheet.ReminderPickerSheetContent
import com.singularity.todo.feature.tasks.presentation.sections.TaskBottomActionBar
import com.singularity.todo.feature.tasks.presentation.sections.TaskChecklistSection
import com.singularity.todo.feature.tasks.presentation.sections.TaskHeroSection
import com.singularity.todo.feature.tasks.presentation.sections.TaskMetaChipsRow
import com.singularity.todo.feature.tasks.presentation.sections.TaskSubtasksSection
import com.singularity.todo.feature.tasks.presentation.sections.RemindersSection
import com.singularity.todo.feature.tasks.presentation.sections.AttachmentsSection
import com.singularity.todo.feature.tasks.presentation.sections.TagsRow
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import com.singularity.todo.core.files.toFilePickerResult
import com.singularity.todo.feature.tasks.domain.port.parseDueTime
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: TaskId,
    onBack: () -> Unit,
    onNavigateToProject: (ProjectId) -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(),
    attachmentsVm: AttachmentsViewModel = koinViewModel(),
) {
    LaunchedEffect(taskId) {
        viewModel.start(taskId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }

    val filePickerLauncher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        file?.let {
            val result = it.toFilePickerResult()
            attachmentsVm.saveFileAttachment(taskId, result.path, result.mimeType)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            if (event is TaskDetailUiEvent.UndoDelete) {
                val result = snackbarHostState.showSnackbar(
                    message = "Task deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.onIntent(TaskDetailIntent.Domain.Restore)
                } else {
                    onBack()
                }
            }
        }
    }

    when (val s = state) {
        TaskDetailUiState.Loading -> LoadingIndicator()
        is TaskDetailUiState.Error -> Text("Error: ${s.message}", modifier = Modifier.padding(16.dp))
        is TaskDetailUiState.Loaded -> {
            val ui = s.ui

            val actions = remember(ui) {
                TaskDetailActions { intent ->
                    when (intent) {
                        is TaskDetailIntent.OpenSheet -> activeSheet = intent.sheet
                        TaskDetailIntent.CloseSheet -> activeSheet = null

                        is TaskDetailIntent.NavigateToTask -> onNavigateToTask(intent.id)
                        is TaskDetailIntent.NavigateToProject -> onNavigateToProject(intent.id)

                        is TaskDetailIntent.Attachment -> {
                            when (intent) {
                                is TaskDetailIntent.Attachment.Delete ->
                                    attachmentsVm.delete(intent.attachmentId)
                                is TaskDetailIntent.Attachment.Click -> Unit
                                TaskDetailIntent.Attachment.PickFile -> filePickerLauncher.launch()
                            }
                        }

                        is TaskDetailIntent.Domain -> viewModel.onIntent(intent)
                    }
                }
            }

            TaskDetailContent(
                ui = ui,
                actions = actions,
                onBack = onBack,
                snackbarHostState = snackbarHostState,
                timeZone = TimeZone.currentSystemDefault(),
            )
        }
    }

    // ─── Sheet overlays ─────────────────────────────────────────────────────────

    when (activeSheet) {
        ActiveSheet.Date -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            DatePickerSheet(
                initialDate = loaded?.task?.dueDate,
                onDateSelected = { date ->
                    loaded?.let {
                        viewModel.onIntent(TaskDetailIntent.Domain.SetDueDate(date))
                    }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Time -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            val currentTime = parseDueTime(loaded?.task?.dueTime)
            TimePickerSheet(
                initialTime = currentTime,
                onTimeSelected = { time ->
                    loaded?.let {
                        viewModel.onIntent(TaskDetailIntent.Domain.SetDueTime(time?.toString()))
                    }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Priority -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            TaskEditorPrioritySheet(
                selected = loaded?.task?.priority ?: TaskPriority.None,
                onSelect = { priority ->
                    loaded?.let {
                        viewModel.onIntent(TaskDetailIntent.Domain.SetPriority(priority))
                    }
                    activeSheet = null
                },
            )
        }
        ActiveSheet.Project -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            ProjectPickerSheet(
                onProjectSelected = { project ->
                    loaded?.let {
                        viewModel.onIntent(TaskDetailIntent.Domain.SetProject(project?.id))
                    }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Tags -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            loaded?.let { ui ->
                TagPickerSheet(
                    selectedTagIds = ui.task.tags.map { it.value }.toSet(),
                    onTagsSelected = { selectedIds ->
                        val tagIds = selectedIds.map { TagId.fromString(it) }
                        viewModel.onIntent(TaskDetailIntent.Domain.SetTags(tagIds))
                    },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.Reminder -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            loaded?.let { ui ->
                ReminderPickerSheetContent(
                    reminders = ui.reminders,
                    onReminderSet = { offset ->
                        viewModel.onIntent(TaskDetailIntent.Domain.SetReminder(offset))
                    },
                    onReminderDeleted = {
                        viewModel.onIntent(TaskDetailIntent.Domain.DeleteReminder)
                    },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.Attachment -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
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
        ActiveSheet.Kind -> {
            val loaded = (state as? TaskDetailUiState.Loaded)?.ui
            loaded?.let { ui ->
                KindSheet(
                    currentKind = ui.task.kind,
                    onSelect = { kind ->
                        viewModel.onIntent(TaskDetailIntent.Domain.SetKind(kind))
                        activeSheet = null
                    },
                    onDismiss = { activeSheet = null },
                )
            }
        }
        ActiveSheet.Delete -> {
            ConfirmDeleteSheet(
                onConfirm = {
                    viewModel.onIntent(TaskDetailIntent.Domain.Delete)
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        ActiveSheet.Archive -> {
            ConfirmArchiveSheet(
                onConfirm = {
                    viewModel.onIntent(TaskDetailIntent.Domain.Archive)
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

private fun TaskDetailUiEvent.toNotification(): Notification = when (this) {
    is TaskDetailUiEvent.Saved -> Notification.Text(title = "Saved", text = message)
    is TaskDetailUiEvent.Error -> Notification.Error(message)
    TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
    else -> Notification.Dismiss
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDetailContent(
    ui: TaskDetailUi,
    actions: TaskDetailActions,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val today = todayInSystemZone()
    val scrollState = rememberScrollState()
    var showMenu by remember { mutableStateOf(false) }

    val dueChipModel = formatDueChip(ui.task.dueDate, ui.task.dueTime, today)
    val (dueDateBg, dueDateFg) = dueChipColors(dueChipModel?.state)
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
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                                onClick = {
                                    showMenu = false
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = modifier
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
                kind = ui.task.kind,
                isSomeday = ui.task.someday,
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
                onPromoteChecklist = { actions.onPromoteChecklistToSubtask(it) },
            )

            RemindersSection(
                reminders = ui.reminders,
                timeZone = timeZone,
                actions = actions,
            )

            AttachmentsSection(
                attachments = ui.attachments,
                actions = actions,
            )

            if (ui.subtasks.isNotEmpty()) {
                TaskSubtasksSection(
                    subtasks = ui.subtasks,
                    actions = actions,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val timestamps = formatTimestampsRelative(
                createdAt = ui.task.createdAt,
                updatedAt = ui.task.updatedAt,
                now = kotlin.time.Clock.System.now(),
            )
            Text(
                text = "${timestamps.created} · ${timestamps.updated}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
