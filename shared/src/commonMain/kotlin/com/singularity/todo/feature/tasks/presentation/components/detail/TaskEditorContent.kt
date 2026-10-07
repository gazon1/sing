// `now` is a required parameter, threaded from the entry point down to
// `TimeEntryEditorSheet`, which takes it as required. It used to be read here as
// `Clock.System.now()` behind a file-level suppression whose recorded reason was
// "threading it is four signature changes across three screens, ending in a call
// no desktop Compose test on this host can execute (#201)". That reason is now
// paid: the signature change is not the obstacle it was described as, and a
// suppression whose justification is "we have not done the work yet" is a
// suppression that outlives its justification.
package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import androidx.compose.material3.MaterialTheme
import kotlin.time.Instant

/**
 * Unified task editor Composable for both Create and View modes.
 *
 * @param titleDraft Current title value.
 * @param onTitleChange Called when title text changes.
 * @param isCompleted Whether the task is completed (affects checkbox appearance).
 * @param onCheckToggle Called when the completion checkbox is toggled. `null` hides the
 *   checkbox — Create mode has no task to complete yet, and a checkbox that does nothing
 *   is worse than no checkbox.
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
 * @param startDate Current start date (null means not set).
 * @param startTime Current start time (null means not set).
 * @param startDateCallbacks Callbacks for the start date row. Null = row is hidden.
 * @param project Current project ID, or null.
 * @param projectCallbacks Callbacks for the project row. Null = row is hidden.
 * @param tags Current list of tag IDs.
 * @param tagsCallbacks Callbacks for the tags row. Null = row is hidden.
 * @param recurrence Current recurrence spec, or null.
 * @param recurrenceCallbacks Callbacks for the recurrence row. Null = row is hidden.
 * @param isPinned Whether the task is pinned.
 * @param pinCallbacks Callbacks for the pin toggle row. Null = row is hidden.
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
    taskId: String,
    titleDraft: String,
    onTitleChange: (String) -> Unit,
    isCompleted: Boolean,
    onCheckToggle: (() -> Unit)?,
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
    startDate: LocalDate? = null,
    startTime: LocalTime? = null,
    startDateCallbacks: DateRowCallbacks? = null,
    project: ProjectId? = null,
    projectCallbacks: RowCallbacks<ProjectId?>? = null,
    tags: List<TagId> = emptyList(),
    tagsCallbacks: RowCallbacks<List<TagId>>? = null,
    recurrence: RecurrenceSpec? = null,
    recurrenceCallbacks: RowCallbacks<RecurrenceSpec?>? = null,
    isPinned: Boolean = false,
    pinCallbacks: ToggleCallbacks? = null,
    estimateMinutes: Int? = null,
    estimateCallbacks: RowCallbacks<Int?>? = null,
    dependsOn: Set<TaskId> = emptySet(),
    availableTasks: List<Task> = emptyList(),
    extraSections: (@Composable () -> Unit)?,
    onSetDependencies: ((Set<TaskId>) -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    menuItems: List<TaskEditorMenuItem>,
    onBack: () -> Unit,
    onAiClick: (() -> Unit)? = null,
    now: Instant,
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
        containerColor = MaterialTheme.colorScheme.background,
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
                taskId = taskId,
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
                onPriorityClick = onPriorityClick ?: { sheets.show(TaskEditorSheet.Priority) },
                onPriorityClear = onPriorityClear,
            )

            // Estimate row
            estimateCallbacks?.let { cb ->
                TaskEditorEstimateRow(
                    estimateMinutes = estimateMinutes,
                    onEstimateClick = cb.onClick ?: { sheets.show(TaskEditorSheet.Estimate) },
                    onEstimateClear = cb.onClear,
                )
            }

            // Due date row
            if (showDueDate) {
                TaskEditorDueDateRow(
                    dueDate = dueDate,
                    dueTime = dueTime,
                    onDueDateClick = onDueDateClick ?: { sheets.show(TaskEditorSheet.Date) },
                    onDueDateClear = onDueDateClear,
                )
            }

            // Start date row
            startDateCallbacks?.let { cb ->
                StartDateRow(
                    startDate = startDate,
                    startTime = startTime,
                    callbacks = cb,
                    onStartDateClick = { sheets.show(TaskEditorSheet.StartDate) },
                )
            }

            // Project row
            projectCallbacks?.let { cb ->
                TaskAttributeCard(
                    icon = Icons.Filled.Folder,
                    label = project?.value ?: "No project",
                    isActive = project != null,
                    onClick = cb.onClick ?: { sheets.show(TaskEditorSheet.Project) },
                    trailingContent = if (cb.onClear != null && project != null) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = "Clear project",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_PROJECT_ROW),
                )
            }

            // Tags row
            tagsCallbacks?.let { cb ->
                TaskAttributeCard(
                    icon = Icons.AutoMirrored.Filled.Label,
                    label = if (tags.isEmpty()) "Add tags" else "${tags.size} tag${if (tags.size > 1) "s" else ""}",
                    isActive = tags.isNotEmpty(),
                    onClick = cb.onClick ?: { sheets.show(TaskEditorSheet.Tags) },
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_TAGS_ROW),
                )
            }

            // Recurrence row
            recurrenceCallbacks?.let { cb ->
                TaskAttributeCard(
                    icon = Icons.Filled.Repeat,
                    label = recurrence?.let { "Repeats" } ?: "No repeat",
                    isActive = recurrence != null,
                    onClick = cb.onClick ?: { sheets.show(TaskEditorSheet.Recurrence) },
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_RECURRENCE_ROW),
                )
            }

            // Pin row
            pinCallbacks?.let { cb ->
                TaskAttributeCard(
                    icon = Icons.Filled.PushPin,
                    label = if (isPinned) "Pinned" else "Not pinned",
                    isActive = isPinned,
                    onClick = cb.onToggle,
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_PIN_ROW),
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
            // The menu renders in its own popup window; the app-root
            // testTagsAsResourceId never reaches it (same as MenuBottomSheet),
            // so the mapping is re-asserted here or every row tag stays
            // invisible to UI automation.
            modifier = Modifier.mapTestTagsAsResourceIds(),

        ) {
            menuItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        showMenu = false
                        item.onClick()
                    },
                    modifier = if (item.testTag != null) {
                        Modifier.testTag(item.testTag)
                    } else {
                        Modifier
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
            startDate = startDate,
            startTime = startTime,
            project = project,
            tags = tags,
            checklist = emptyList(),
            attachments = emptyList(),
            recurrence = recurrence,
            isPinned = isPinned,
            estimateMinutes = estimateMinutes,
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
                // Null passes through as null. `?: {}` turned "this row has no tap
                // target" into "this row has a tap target that does nothing", and the
                // row rendered a ripple either way.
                onClick = onPriorityClick,
                onClear = onPriorityClear,
            ),
            dueDate = DateRowCallbacks(
                onChangeDate = onDueDateSelect,
                onChangeTime = onDueTimeSelect,
                onClick = onDueDateClick,
                onClear = onDueDateClear,
            ),
            startDate = startDateCallbacks,
            project = projectCallbacks,
            tags = tagsCallbacks,
            recurrence = recurrenceCallbacks,
            pin = pinCallbacks,
            estimate = estimateCallbacks,
            dependencies = RowCallbacks(
                onChange = { onSetDependencies?.invoke(it) },
                onClick = null,
                onClear = null,
            ),
            checklist = null,
            attachments = null,
            onTimeEntryAdd = null,
            onTimeEntrySave = null,
            bottomBar = null,
            menuItems = menuItems,
        ),
        activeSheet = sheets.active,
        // Passed down so the time-entry sheet's "now" default comes from the one
        // place that resolved the clock, rather than from a clock this screen
        // reaches for itself (#91). `now` is required, so a caller that has not
        // decided what "now" is cannot compile.
        now = now,
        onSheetDismiss = { sheets.dismiss() },
    )
}

@Composable
private fun StartDateRow(
    startDate: LocalDate?,
    startTime: LocalTime?,
    callbacks: DateRowCallbacks,
    onStartDateClick: () -> Unit,
) {
    val label = when {
        startDate == null -> "No start date"
        startTime != null -> "$startDate $startTime"
        else -> startDate.toString()
    }
    TaskAttributeCard(
        icon = Icons.Filled.CalendarToday,
        label = label,
        isActive = startDate != null,
        onClick = callbacks.onClick ?: onStartDateClick,
        modifier = Modifier.testTag(TestTags.TASK_EDITOR_START_DATE_ROW),
    )
}

/**
 * Overload that unpacks [TaskEditorModel] and [TaskEditorCallbacks] into explicit parameters.
 * Used by [com.singularity.todo.feature.tasks.presentation.screen.TaskDetailScreen].
 */
@Composable
fun TaskEditorContent(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    isCompleted: Boolean = false,
    now: Instant,
) {
    TaskEditorContent(
        taskId = model.taskId?.value ?: "",
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
        startDate = model.startDate,
        startTime = model.startTime,
        startDateCallbacks = callbacks.startDate,
        project = model.project,
        projectCallbacks = callbacks.project,
        tags = model.tags,
        tagsCallbacks = callbacks.tags,
        recurrence = model.recurrence,
        recurrenceCallbacks = callbacks.recurrence,
        isPinned = model.isPinned,
        pinCallbacks = callbacks.pin,
        estimateMinutes = model.estimateMinutes,
        estimateCallbacks = callbacks.estimate,
        dependsOn = model.dependsOn,
        availableTasks = model.availableTasks,
        extraSections = null,
        onSetDependencies = callbacks.dependencies?.onChange,
        bottomBar = callbacks.bottomBar,
        menuItems = callbacks.menuItems,
        onBack = callbacks.onBack,
        onAiClick = callbacks.onAiClick,
        now = now,
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────

private fun priorityLabel(priority: TaskPriority): String = when (priority) {
    TaskPriority.None -> "No priority"
    TaskPriority.Low -> "Low priority"
    TaskPriority.Medium -> "Medium priority"
    TaskPriority.High -> "High priority"
    TaskPriority.Urgent -> "Urgent"
}

private fun dueDateLabel(date: LocalDate?, time: LocalTime?): String {
    if (date == null) return "Добавить дату"
    val dateStr = date.toString()
    return if (time != null) {
        "$dateStr ${
            time.toString()
                .take(5)
        }"
    } else {
        dateStr
    }
}

// ===== Preview =====

@Preview
@Composable
private fun TaskEditorContentEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TaskEditorContent(
        taskId = "",
        titleDraft = "",
        onTitleChange = {},
        isCompleted = false,
        onCheckToggle = null,
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
        now = PreviewSamples.now,
    )
}

@Preview
@Composable
private fun TaskEditorContentFilledPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val today = PreviewSamples.today
    TaskEditorContent(
        taskId = "",
        titleDraft = "Buy groceries",
        onTitleChange = {},
        isCompleted = false,
        onCheckToggle = null,
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
            TaskEditorMenuItem("Archive", onClick = {}),
            TaskEditorMenuItem("Delete", onClick = {}),

        ),
        onBack = {},
        now = PreviewSamples.now,
    )
}

@Preview
@Composable
private fun TaskEditorContentDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    val today = PreviewSamples.today
    val sampleTask = PreviewSamples.task(id = "t2", title = "Review PR")
    TaskEditorContent(
        taskId = "",
        titleDraft = "Review PR",
        onTitleChange = {},
        isCompleted = false,
        onCheckToggle = null,
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
            TaskEditorMenuItem("Archive", onClick = {}),
            TaskEditorMenuItem("Delete", onClick = {}),

        ),
        onBack = {},
        now = PreviewSamples.now,
    )
}
