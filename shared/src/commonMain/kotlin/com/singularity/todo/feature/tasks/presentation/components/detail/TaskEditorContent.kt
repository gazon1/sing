package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Unified task editor Composable for both Create and View modes.
 *
 * Uses slot API: view-specific content is passed as nullable lambdas.
 * This eliminates the ~46% code duplication between TaskCreateContent and TaskDetailViewContent.
 *
 * @param titleDraft Current title value.
 * @param onTitleChange Called when title text changes.
 * @param isCompleted Whether the task is completed (affects checkbox appearance).
 * @param onCheckToggle Called when the completion checkbox is toggled. Pass empty lambda in Create mode.
 * @param descriptionDraft Current description value.
 * @param onDescriptionChange Called when description text changes.
 * @param priority Current priority value.
 * @param onPrioritySelect Called when a priority is selected in the sheet.
 * @param onPriorityClear Called when the priority X button is tapped. Pass null in View mode (no X shown).
 * @param dueDate Current due date (null means not set).
 * @param dueTime Current due time (null means not set).
 * @param onDueDateSelect Called when a date is selected in the date picker sheet.
 * @param onDueDateClear Called when the due date X button is tapped. Pass null in View mode.
 * @param onDueTimeSelect Called when a time is selected in the time picker sheet.
 * @param extraSections Optional composable for View-mode-only sections (checklist, project, tags, timestamps, etc.).
 * @param bottomBar Optional bottom bar content. Typically [TaskSaveBar] in Create mode, null in View mode.
 * @param menuItems List of dropdown menu items. Shown when non-empty. Typically Archive/Delete in View mode.
 * @param onBack Called when the back button is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorContent(
    titleDraft: String,
    onTitleChange: (String) -> Unit,
    isCompleted: Boolean,
    onCheckToggle: () -> Unit,
    descriptionDraft: String,
    onDescriptionChange: (String) -> Unit,
    priority: TaskPriority,
    onPrioritySelect: (TaskPriority) -> Unit,
    onPriorityClear: (() -> Unit)?,
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    onDueDateSelect: (LocalDate?) -> Unit,
    onDueDateClear: (() -> Unit)?,
    onDueTimeSelect: (LocalTime?) -> Unit,
    showDueDate: Boolean = true,
    /** Click on the Priority row opens the priority picker sheet. */
    onPriorityClick: (() -> Unit)? = null,
    /** Click on the Due Date row opens the date picker sheet. */
    onDueDateClick: (() -> Unit)? = null,
    /** IDs of tasks this task depends on. Opens the [DependencyPickerSheet]. */
    dependsOn: Set<TaskId> = emptySet(),
    /** Tasks available for dependency selection. */
    availableTasks: List<Task> = emptyList(),
    extraSections: (@Composable () -> Unit)?,
    /** Called when the user confirms a new set of dependencies in [DependencyPickerSheet]. */
    onSetDependencies: ((Set<TaskId>) -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    menuItems: List<TaskEditorMenuItem>,
    onBack: () -> Unit,
) {
    val sheets = rememberDialogState<TaskEditorSheet>()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TaskDetailTopBar(
                onBackClick = onBack,
                onMoreClick = { showMenu = true },
            )
        },
        bottomBar = bottomBar
            ?: {},
        containerColor = TaskColors.Background,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaskSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(TaskSpacing.md),
        ) {
            // Title row
            TaskTitleRow(
                title = titleDraft,
                isCompleted = isCompleted,
                onTitleChange = onTitleChange,
                onCheckToggle = onCheckToggle,
            )

            // Description
            TaskDescriptionField(
                description = descriptionDraft,
                onDescriptionChange = onDescriptionChange,
            )

            // View-mode extra sections
            extraSections?.invoke()

            // Dependencies card — shown when task has dependencies
            if (dependsOn.isNotEmpty() && availableTasks.isNotEmpty()) {
                val depTitles = dependsOn.mapNotNull { depId ->
                    availableTasks.find { it.id == depId }?.title?.ifBlank { null }
                }
                TaskAttributeCard(
                    icon = Icons.Filled.Block,
                    label = if (depTitles.isNotEmpty()) {
                        depTitles.joinToString(", ")
                    } else {
                        "${dependsOn.size} dependency${if (dependsOn.size > 1) "s" else ""}"
                    },
                    isActive = true,
                    onClick = { sheets.show(TaskEditorSheet.Dependencies) },
                )
            }

            // Priority attribute
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(
                        onClick = onPriorityClick
                            ?: { sheets.show(TaskEditorSheet.Priority) })
                    .then(
                        Modifier.padding(
                            horizontal = TaskSpacing.cardPaddingHorizontal,
                            vertical = TaskSpacing.cardPaddingVertical,
                        ),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Flag,
                    contentDescription = null,
                    tint = if (priority != TaskPriority.None) TaskColors.AccentBlue else TaskColors.TextSecondary,
                    modifier = Modifier.size(TaskSpacing.iconSize),
                )
                Spacer(Modifier.width(TaskSpacing.lg))
                Text(
                    text = priorityLabel(priority),
                    color = if (priority != TaskPriority.None) TaskColors.TextPrimary else TaskColors.TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = if (priority != TaskPriority.None) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (priority != TaskPriority.None && onPriorityClear != null) {
                    IconButton(onClick = onPriorityClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Сбросить приоритет",
                            tint = TaskColors.TextSecondary,
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            // Due date attribute — only shown when showDueDate is true
            if (showDueDate) {
                val label = dueDateLabel(dueDate, dueTime)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(
                            onClick = onDueDateClick
                                ?: { sheets.show(TaskEditorSheet.Date) })
                        .padding(
                            horizontal = TaskSpacing.cardPaddingHorizontal,
                            vertical = TaskSpacing.cardPaddingVertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarToday,
                        contentDescription = null,
                        tint = if (dueDate != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
                        modifier = Modifier.size(TaskSpacing.iconSize),
                    )
                    Spacer(Modifier.width(TaskSpacing.lg))
                    Text(
                        text = label,
                        color = if (dueDate != null) TaskColors.TextPrimary else TaskColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = if (dueDate != null) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (dueDate != null && onDueDateClear != null) {
                        IconButton(onClick = onDueDateClear) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Сбросить дату",
                                tint = TaskColors.TextSecondary,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(TaskSpacing.xl))
        }
    }

    // Overflow menu — delegated to TaskEditorMenuHost for consistent styling
    TaskEditorMenuHost(
        menuItems = menuItems,
        showMenu = showMenu,
        onDismiss = { showMenu = false },
    )

    // Sheets — delegate to TaskEditorSheetsHost which handles all 12 variants
    TaskEditorSheetsHost(
        model = TaskEditorModel(
            taskId = null,
            titleDraft = titleDraft,
            descriptionDraft = descriptionDraft,
            priority = priority,
            dueDate = dueDate,
            dueTime = dueTime,
            startDate = null,
            startTime = null,
            project = null,
            tags = emptyList(),
            checklist = emptyList(),
            attachments = emptyList(),
            recurrence = null,
            isPinned = false,
            dependsOn = dependsOn,
            availableTasks = availableTasks,
        ),
        callbacks = TaskEditorCallbacks(
            onBack = onBack,
            onTitleChange = onTitleChange,
            onCheckToggle = onCheckToggle,
            onDescriptionChange = onDescriptionChange,
            priority = RowCallbacks(
                onChange = onPrioritySelect,
                onClick = onPriorityClick ?: {},
                onClear = onPriorityClear,
            ),
            dueDate = DateRowCallbacks(
                onChangeDate = onDueDateSelect,
                onChangeTime = onDueTimeSelect,
                onClick = onDueDateClick ?: {},
                onClear = onDueDateClear,
            ),
            startDate = null,
            project = null,
            tags = null,
            recurrence = null,
            pin = null,
            dependencies = RowCallbacks(
                onChange = { onSetDependencies?.invoke(it) },
                onClick = {},
                onClear = null,
            ),
            checklist = null,
            attachments = null,
            bottomBar = null,
            menuItems = menuItems,
        ),
        activeSheet = sheets.active,
        onSheetDismiss = { sheets.dismiss() },
    )
}

/**
 * Overload that unpacks [TaskEditorModel] and [TaskEditorCallbacks] into explicit parameters.
 * Used by [TaskDetailViewScreen] which constructs model + callbacks separately.
 *
 * Note: fields in [TaskEditorModel] that have no corresponding explicit parameter
 * (startDate, startTime, project, tags, recurrence, pin, checklist, attachments)
 * are not rendered by this overload. They are controlled via `extraSections` in the
 * explicit-parameter overload.
 */
@Composable
fun TaskEditorContent(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    isCompleted: Boolean = false,
) {
    TaskEditorContent(
        titleDraft = model.titleDraft,
        onTitleChange = callbacks.onTitleChange,
        isCompleted = isCompleted,
        onCheckToggle = callbacks.onCheckToggle,
        descriptionDraft = model.descriptionDraft,
        onDescriptionChange = callbacks.onDescriptionChange,
        priority = model.priority,
        onPrioritySelect = callbacks.priority?.onChange ?: {},
        onPriorityClear = callbacks.priority?.onClear,
        dueDate = model.dueDate,
        dueTime = model.dueTime,
        onDueDateSelect = callbacks.dueDate?.onChangeDate ?: {},
        onDueDateClear = callbacks.dueDate?.onClear,
        onDueTimeSelect = callbacks.dueDate?.onChangeTime ?: {},
        showDueDate = true,
        onPriorityClick = callbacks.priority?.onClick,
        onDueDateClick = callbacks.dueDate?.onClick,
        dependsOn = model.dependsOn,
        availableTasks = model.availableTasks,
        extraSections = null,
        onSetDependencies = callbacks.dependencies?.onChange,
        bottomBar = callbacks.bottomBar,
        menuItems = callbacks.menuItems,
        onBack = callbacks.onBack,
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────

private fun priorityLabel(priority: TaskPriority): String =
    when (priority) {
        TaskPriority.None -> "No priority"
        TaskPriority.Low -> "Low priority"
        TaskPriority.Medium -> "Medium priority"
        TaskPriority.High -> "High priority"
        TaskPriority.Urgent -> "Urgent"
    }

private fun dueDateLabel(date: LocalDate?, time: LocalTime?): String {
    if (date == null) return "Добавить дату"
    val dateStr = date.toString()
    return if (time != null) "$dateStr ${
        time.toString()
            .take(5)
    }" else dateStr
}

// ===== Preview =====

@Preview
@Composable
private fun TaskEditorContentEmptyPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        TaskEditorContent(
            titleDraft = "",
            onTitleChange = {},
            isCompleted = false,
            onCheckToggle = {},
            descriptionDraft = "",
            onDescriptionChange = {},
            priority = TaskPriority.None,
            onPrioritySelect = {},
            onPriorityClear = null,
            dueDate = null,
            dueTime = null,
            onDueDateSelect = {},
            onDueDateClear = null,
            onDueTimeSelect = {},
            dependsOn = emptySet(),
            availableTasks = emptyList(),
            extraSections = null,
            onSetDependencies = null,
            bottomBar = null,
            menuItems = emptyList(),
            onBack = {},
        )
    }

@Preview
@Composable
private fun TaskEditorContentFilledPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        val today = PreviewSamples.today
        TaskEditorContent(
            titleDraft = "Buy groceries",
            onTitleChange = {},
            isCompleted = false,
            onCheckToggle = {},
            descriptionDraft = "Milk, eggs, bread",
            onDescriptionChange = {},
            priority = TaskPriority.High,
            onPrioritySelect = {},
            onPriorityClear = {},
            dueDate = today,
            dueTime = LocalTime(14, 30),
            onDueDateSelect = {},
            onDueDateClear = {},
            onDueTimeSelect = {},
            dependsOn = emptySet(),
            availableTasks = emptyList(),
            extraSections = null,
            onSetDependencies = null,
            bottomBar = null,
            menuItems = listOf(
                TaskEditorMenuItem("Archive") {},
                TaskEditorMenuItem("Delete") {},
            ),
            onBack = {},
        )
    }

@Preview
@Composable
private fun TaskEditorContentDarkPreview() =
    PreviewThemed(darkTheme = true, useSurface = false) {
        val today = PreviewSamples.today
        val sampleTask = PreviewSamples.task(id = "t2", title = "Review PR")
        TaskEditorContent(
            titleDraft = "Review PR",
            onTitleChange = {},
            isCompleted = false,
            onCheckToggle = {},
            descriptionDraft = "",
            onDescriptionChange = {},
            priority = TaskPriority.Urgent,
            onPrioritySelect = {},
            onPriorityClear = {},
            dueDate = today,
            dueTime = null,
            onDueDateSelect = {},
            onDueDateClear = {},
            onDueTimeSelect = {},
            dependsOn = setOf(sampleTask.id),
            availableTasks = listOf(sampleTask),
            extraSections = null,
            onSetDependencies = {},
            bottomBar = null,
            menuItems = listOf(
                TaskEditorMenuItem("Archive") {},
                TaskEditorMenuItem("Delete") {},
            ),
            onBack = {},
        )
    }
