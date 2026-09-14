package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.core.ui.components.formatTimestampsRelative
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorPrioritySheet
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.feature.tasks.presentation.state.CreateActiveSheet
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlin.time.Instant

/**
 * Content for TaskDetail View mode.
 * Owns local draft state for title/description (sync with VM via LaunchedEffect).
 * Owns sheet/dropdown state (not lifted to avoid Host explosion).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailViewContent(
    ui: TaskDetailUi,
    onIntent: (TaskDetailIntent) -> Unit,
    onBack: () -> Unit,
    onNavigateToProject: (ProjectId) -> Unit,
) {
    var activeSheet by remember { mutableStateOf<CreateActiveSheet?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    // Local drafts — sync with external VM state to avoid stale text fields.
    var titleDraft by remember { mutableStateOf(ui.task.title) }
    var descriptionDraft by remember { mutableStateOf(ui.task.description ?: "") }

    LaunchedEffect(ui.task.title) {
        if (titleDraft != ui.task.title) titleDraft = ui.task.title
    }
    LaunchedEffect(ui.task.description) {
        val new = ui.task.description ?: ""
        if (descriptionDraft != new) descriptionDraft = new
    }

    Scaffold(
        topBar = {
            TaskDetailTopBar(
                onBackClick = onBack,
                onMoreClick = { showMenu = true },
            )
        },
        containerColor = TaskColors.Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaskSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(TaskSpacing.md),
        ) {
            TaskTitleRow(
                title = titleDraft,
                isCompleted = ui.task.isCompleted,
                onTitleChange = { newTitle ->
                    titleDraft = newTitle
                    onIntent(TaskDetailIntent.Domain.TitleChanged(newTitle))
                },
                onCheckToggle = { onIntent(TaskDetailIntent.Domain.ToggleComplete) },
            )

            TaskDescriptionField(
                description = descriptionDraft,
                onDescriptionChange = { newDesc ->
                    descriptionDraft = newDesc
                    onIntent(TaskDetailIntent.Domain.DescriptionChanged(newDesc))
                },
            )

            // Checklist
            if (ui.checklist.isNotEmpty()) {
                TaskChecklistCard(
                    itemCount = ui.checklist.count { !it.isCompleted },
                    onClick = { /* TODO: navigate to checklist detail */ },
                )
            }

            // Project
            ui.project?.let { project ->
                TaskAttributeCard(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    label = project.name,
                    isActive = true,
                    onClick = { onNavigateToProject(project.id) },
                )
            }

            // Tags
            if (ui.tags.isNotEmpty()) {
                TaskAttributeCard(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    label = ui.tags.joinToString { it.name },
                    isActive = true,
                    onClick = { activeSheet = CreateActiveSheet.Tags },
                )
            }

            // Priority — guard externally so we never call TaskPriorityCard with None
            if (ui.task.priority != TaskPriority.None) {
                TaskPriorityCard(
                    priority = ui.task.priority,
                    onClick = { activeSheet = CreateActiveSheet.Priority },
                )
            }

            // Due date
            ui.task.dueDate?.let { date ->
                TaskAttributeCard(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    label = date.toString(),
                    isActive = true,
                    onClick = { activeSheet = CreateActiveSheet.Date },
                )
            }

            // Reminders
            if (ui.reminders.isNotEmpty()) {
                TaskReminderGroup(
                    reminder = ui.reminders.firstOrNull()?.toString() ?: "",
                    repeatRule = null,
                    deadline = null,
                    onReminderClick = { activeSheet = CreateActiveSheet.Reminder },
                    onRepeatClick = { },
                    onDeadlineClick = { },
                )
            }

            // Subtasks
            if (ui.subtasks.isNotEmpty()) {
                TaskCounterCard(
                    icon = Icons.Filled.Checklist,
                    label = "Подзадачи",
                    count = ui.subtasks.size,
                    onClick = { /* TODO: navigate to subtasks */ },
                )
            }

            // Attachments
            if (ui.attachments.isNotEmpty()) {
                TaskCounterCard(
                    icon = Icons.Default.AttachFile,
                    label = "Файлы",
                    count = ui.attachments.size,
                    onClick = { /* TODO: navigate to attachments */ },
                )
            }

            Spacer(modifier = Modifier.height(TaskSpacing.xl))

            // Timestamps
            val timestamps = formatTimestampsRelative(ui.task.createdAt, ui.task.updatedAt, Clock.now())
            Text(
                text = "${timestamps.created} · ${timestamps.updated}",
                style = MaterialTheme.typography.bodySmall,
                color = TaskColors.TextSecondary,
            )

            Spacer(modifier = Modifier.height(TaskSpacing.xl))
        }
    }

    // ─── Dropdown menu ───────────────────────────────────────────────────
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
    ) {
        DropdownMenuItem(
            text = { Text("Архивировать") },
            onClick = {
                showMenu = false
                onIntent(TaskDetailIntent.Domain.Archive)
            },
        )
        DropdownMenuItem(
            text = { Text("Удалить") },
            onClick = {
                showMenu = false
                onIntent(TaskDetailIntent.Domain.Delete)
            },
        )
    }

    // ─── Sheets ───────────────────────────────────────────────────────────
    when (activeSheet) {
        CreateActiveSheet.Date -> DatePickerSheet(
            initialDate = ui.task.dueDate,
            onDateSelected = { date ->
                onIntent(TaskDetailIntent.Domain.SetDueDate(date))
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        CreateActiveSheet.Time -> TimePickerSheet(
            initialTime = ui.task.dueTime,
            onTimeSelected = { time ->
                onIntent(TaskDetailIntent.Domain.SetDueTime(time))
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        CreateActiveSheet.Priority -> TaskEditorSheetHost(
            title = "Приоритет",
            onClose = { activeSheet = null },
        ) {
            TaskEditorPrioritySheet(
                selected = ui.task.priority,
                onSelect = { priority ->
                    onIntent(TaskDetailIntent.Domain.SetPriority(priority))
                    activeSheet = null
                },
            )
        }
        CreateActiveSheet.Tags -> {
            // TODO: TagPickerSheet when available
            activeSheet = null
        }
        CreateActiveSheet.Project -> {
            // TODO: ProjectPickerSheet when available
            activeSheet = null
        }
        CreateActiveSheet.Reminder -> {
            // TODO: ReminderPicker when available
            activeSheet = null
        }
        CreateActiveSheet.Kind,
        CreateActiveSheet.Attachment,
        null -> { /* no-op */ }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Composable
private fun TaskDetailViewContentPreview(
    ui: TaskDetailUi = TaskDetailUi(
        task = Task(
            id = TaskId("t1"),
            title = "Buy groceries",
            description = "Milk, eggs, bread",
            priority = TaskPriority.Medium,
            kind = TaskKind.Task,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            userId = UserId("u1"),
        ),
    ),
) {
    TaskDetailViewContent(
        ui = ui,
        onIntent = { },
        onBack = { },
        onNavigateToProject = { },
    )
}

@Preview
@Composable
private fun TaskDetailViewContentDefaultPreview() = TaskDetailViewContentPreview()

@Preview
@Composable
private fun TaskDetailViewContentHighPriorityPreview() = TaskDetailViewContentPreview(
    ui = TaskDetailUi(
        task = Task(
            id = TaskId("t2"),
            title = "URGENT: Deploy to production",
            description = null,
            priority = TaskPriority.Urgent,
            kind = TaskKind.Task,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            userId = UserId("u1"),
        ),
    ),
)
