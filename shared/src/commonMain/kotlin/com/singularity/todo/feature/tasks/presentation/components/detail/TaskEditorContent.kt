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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.components.rememberDialogState
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
 * @param titleDraft Current title value.
 * @param onTitleChange Called when title text changes.
 * @param isCompleted Whether the task is completed (affects checkbox appearance).
 * @param onCheckToggle Called when the completion checkbox is toggled. Pass empty lambda in Create mode.
 * @param descriptionDraft Current description value.
 * @param onDescriptionChange Called when description text changes.
 * @param priority Current priority value.
 * @param onPrioritySelect Called when a priority is selected in the sheet.
 * @param onPriorityClear Called when the priority X button is tapped. Pass null in Create mode (no X shown).
 * @param dueDate Current due date (null means not set).
 * @param dueTime Current due time (null means not set).
 * @param onDueDateSelect Called when a date is selected in the date picker sheet.
 * @param onDueDateClear Called when the due date X button is tapped. Pass null in Create mode.
 * @param onDueTimeSelect Called when a time is selected in the time picker sheet.
 * @param showDueDate Controls whether the due date row is rendered.
 * @param onPriorityClick Click on the Priority row opens the priority picker sheet.
 * @param onDueDateClick Click on the Due Date row opens the date picker sheet.
 * @param dependsOn IDs of tasks this task depends on.
 * @param availableTasks Tasks available for dependency selection.
 * @param extraSections Optional composable for View-mode-only sections (checklist, project, tags, timestamps, etc.).
 * @param onSetDependencies Called when the user confirms a new set of dependencies.
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
    onPriorityClick: (() -> Unit)? = null,
    onDueDateClick: (() -> Unit)? = null,
    dependsOn: Set<TaskId> = emptySet(),
    availableTasks: List<Task> = emptyList(),
    extraSections: (@Composable () -> Unit)?,
    onSetDependencies: ((Set<TaskId>) -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    menuItems: List<TaskEditorMenuItem>,
    onBack: () -> Unit,
    onAiClick: (() -> Unit)? = null,
) {
    val sheets = rememberDialogState<TaskEditorSheet>()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TaskDetailTopBar(
                onBackClick = onBack,
                onMoreClick = { showMenu = true },
                onAiClick = onAiClick,
            )
        },
        bottomBar = bottomBar ?: {},
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

            // Dependencies card
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

            // Priority row
            TaskEditorPriorityRow(
                priority = priority,
                onPriorityClick = onPriorityClick,
                onPriorityClear = onPriorityClear,
            )

            // Due date row
            if (showDueDate) {
                TaskEditorDueDateRow(
                    dueDate = dueDate,
                    dueTime = dueTime,
                    onDueDateClick = onDueDateClick,
                    onDueDateClear = onDueDateClear,
                )
            }

            Spacer(modifier = Modifier.height(TaskSpacing.xl))
        }
    }

    // Dropdown menu
    if (menuItems.isNotEmpty()) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            menuItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        showMenu = false
                        item.onClick()
                    },
                )
            }
        }
    }

    // Sheets
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
                onClick = null,
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
 * Used by [com.singularity.todo.feature.tasks.presentation.screen.TaskDetailViewScreen].
 */
@Composable
fun TaskEditorContent(model: TaskEditorModel, callbacks: TaskEditorCallbacks, isCompleted: Boolean = false) {
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
        onAiClick = callbacks.onAiClick,
    )
}
