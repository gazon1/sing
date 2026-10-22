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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AttachFile
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
import com.singularity.todo.core.ui.components.DialogState
import com.singularity.todo.core.ui.textfields.OwnedText
import com.singularity.todo.core.ui.textfields.StaticText
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
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
 * The fields are already bridged: the caller owns the source, knows its kind, and hands
 * over the state. This composable renders the editor and never asks which kind of upstream
 * it has — the two editors that serve the two kinds are [TaskEditorContent] and
 * [TaskCreateEditorContent].
 *
 * @param titleState The title's field state, bridged by the caller.
 * @param isCompleted Whether the task is completed (affects checkbox appearance).
 * @param onCheckToggle Called when the completion checkbox is toggled. `null` hides the
 *   checkbox — Create mode has no task to complete yet, and a checkbox that does nothing
 *   is worse than no checkbox.
 * @param descriptionState The description's field state, bridged by the caller.
 * @param priority Current priority value.
 * @param onPriorityClear Called when the priority X button is tapped. Pass null in Create mode (no X shown).
 * @param dueDate Current due date (null means not set).
 * @param dueTime Current due time (null means not set).
 * @param onDueDateClear Called when the due date X button is tapped. Pass null in Create mode.
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
 * @param blockedBy IDs of tasks this task depends on.
 * @param availableTasks Tasks available for dependency selection.
 * @param extraSections Optional composable for View-mode-only sections (checklist, project, tags, timestamps, etc.).
 * @param bottomBar Optional bottom bar content. Typically [TaskSaveBar] in Create mode, null in View mode.
 * @param menuItems List of dropdown menu items. Shown when non-empty. Typically Archive/Delete in View mode.
 * @param onBack Called when the back button is tapped.
 * @param sheets The state the rows open their sheets through. Resolved by the caller, so the
 *   row that opens a sheet and the host that renders it share one state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorContent(
    taskId: String,
    titleState: TextFieldState,
    isCompleted: Boolean,
    onCheckToggle: (() -> Unit)?,
    descriptionState: TextFieldState,
    priority: TaskPriority,
    onPriorityClear: (() -> Unit)?,
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    onDueDateClear: (() -> Unit)?,
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
    blockedBy: Set<TaskId> = emptySet(),
    availableTasks: List<Task> = emptyList(),
    checklistCount: Int = 0,
    attachmentsCount: Int = 0,
    checklistCallbacks: ChecklistCallbacks? = null,
    attachmentsCallbacks: AttachmentsCallbacks? = null,
    showAttachmentsRow: Boolean = true,
    extraSections: (@Composable () -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    menuItems: List<TaskEditorMenuItem>,
    onBack: () -> Unit,
    onAiClick: (() -> Unit)? = null,
    sheets: DialogState<TaskEditorSheet>,
) {
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
                titleState = titleState,
                isCompleted = isCompleted,
                onCheckToggle = onCheckToggle,
            )

            // Description
            TaskDescriptionField(
                descriptionState = descriptionState,
            )

            // View-mode extra sections
            extraSections?.invoke()

            // Dependencies card
            if (blockedBy.isNotEmpty() && availableTasks.isNotEmpty()) {
                val depTitles = blockedBy.mapNotNull { depId ->
                    availableTasks.find { it.id == depId }?.title?.ifBlank { null }
                }
                TaskAttributeCard(
                    icon = Icons.Filled.Block,
                    label = if (depTitles.isNotEmpty()) {
                        depTitles.joinToString(", ")
                    } else {
                        "${blockedBy.size} dependency${if (blockedBy.size > 1) "s" else ""}"
                    },
                    isActive = true,
                    onClick = { sheets.show(TaskEditorSheet.Dependencies) },
                )
            }

            // Checklist row
            //
            // The row is drawn when the caller gives the editor a checklist to edit —
            // the create flow does, the detail flow does not (it lists the items under
            // the fields instead). A row that is not drawn is a row that cannot be
            // pressed; what it opened was the sheet, and the sheet is where the items
            // are added, because that is the only surface with an input for them.
            checklistCallbacks?.let { cb ->
                TaskAttributeCard(
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    label = if (checklistCount > 0) "Checklist ($checklistCount)" else "Checklist",
                    isActive = checklistCount > 0,
                    onClick = cb.onOpen,
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_CHECKLIST_ROW),
                )
            }

            // Attachments row
            //
            // The row and the sheet are two different things that share one bundle
            // of callbacks: the row opens the sheet, and the sheet needs the
            // callbacks to add a link or a file. A caller that lists its
            // attachments under the fields instead — the task detail does — draws
            // no row here, and the sheet it opens from there still works.
            if (showAttachmentsRow) {
                attachmentsCallbacks?.let { cb ->
                    TaskAttributeCard(
                        icon = Icons.Filled.AttachFile,
                        label = if (attachmentsCount > 0) {
                            "Attachments ($attachmentsCount)"
                        } else {
                            "Attachments"
                        },
                        isActive = attachmentsCount > 0,
                        onClick = cb.onOpen,
                        modifier = Modifier.testTag(TestTags.TASK_EDITOR_ATTACHMENTS_ROW),
                    )
                }
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
                    labelTestTag = TestTags.TASK_EDITOR_DUE_DATE_ROW_LABEL,
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
                    testTag = TestTags.TASK_EDITOR_RECURRENCE_LABEL,
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
                    modifier = Modifier
                        .testTag(TestTags.TASK_EDITOR_PIN_ROW),
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
 * The editor of a task whose upstream **owns** the title and the description.
 *
 * The upstream may replace the value with anything, including a value the field produced
 * earlier, so the source carries the count of the replacements. That count is the owner's
 * business: the editor does not keep it, and it does not decide whether the count moved —
 * it hands the source to the bridge and shows what comes back.
 *
 * @param titleSource the title's source. The owner builds it, and its type says who may
 *   replace the title.
 * @param descriptionSource the description's source, in the same shape.
 * @param onTitleChange called with the title the user typed.
 * @param onDescriptionChange called with the description the user typed.
 * @param sheets the state the sheets are opened through and rendered from. The caller's rows
 *   open a sheet through this state, so the caller owns it and hands it over — there is no
 *   default, because a default is a second state, and two states are two states that will
 *   not agree.
 */
@Composable
fun TaskEditorContent(
    model: TaskEditorModel,
    titleSource: OwnedText,
    descriptionSource: OwnedText,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    callbacks: TaskEditorCallbacks,
    isCompleted: Boolean = false,
    now: Instant,
    sheets: DialogState<TaskEditorSheet>,
    showAttachmentsRow: Boolean = true,
) {
    TaskEditorScaffold(
        model = model,
        callbacks = callbacks,
        titleState = rememberBridgedTextFieldState(titleSource, onTitleChange),
        descriptionState = rememberBridgedTextFieldState(descriptionSource, onDescriptionChange),
        isCompleted = isCompleted,
        now = now,
        sheets = sheets,
        showAttachmentsRow = showAttachmentsRow,
    )
}

/**
 * The editor of a task whose upstream **loads** the title and the description once.
 *
 * The upstream replaces the value only with one the field never produced, so the echo guard
 * needs no lifting and there is no count to read. That is a different kind of source, and
 * it is a different editor: the kind is a compile-time property of the parameter, not a
 * question this component answers at runtime.
 *
 * @param titleSource the title's source. The owner builds it, and its type says who may
 *   replace the title.
 * @param descriptionSource the description's source, in the same shape.
 * @param onTitleChange called with the title the user typed.
 * @param onDescriptionChange called with the description the user typed.
 * @param sheets the state the sheets are opened through and rendered from. The caller's rows
 *   open a sheet through this state, so the caller owns it and hands it over — there is no
 *   default, because a default is a second state, and two states are two states that will
 *   not agree.
 */
@Composable
fun TaskCreateEditorContent(
    model: TaskEditorModel,
    titleSource: StaticText,
    descriptionSource: StaticText,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    callbacks: TaskEditorCallbacks,
    now: Instant,
    sheets: DialogState<TaskEditorSheet>,
) {
    TaskEditorScaffold(
        model = model,
        callbacks = callbacks,
        titleState = rememberBridgedTextFieldState(titleSource, onTitleChange),
        descriptionState = rememberBridgedTextFieldState(descriptionSource, onDescriptionChange),
        isCompleted = false,
        now = now,
        sheets = sheets,
    )
}

/**
 * Unpacks [TaskEditorModel] and [TaskEditorCallbacks] into explicit parameters.
 *
 * The fields are already bridged by the caller, so this is where the two kinds meet: the
 * editor renders what it is given and never asks which kind of upstream it has.
 *
 * The scaffold also owns the sheets. There is exactly one state, and the row that opens a
 * sheet and the host that renders it share it — the state the caller's rows show into is
 * the state the host reads. The editor used to build a second model and a second callback
 * bundle for the host, which meant the host answered from values the editor no longer had:
 * a task id of `null` for a task that exists, and an empty checklist for a draft that had
 * items.
 *
 * The state is handed over, not defaulted. A default would be a second state, and the row
 * that opens a sheet and the host that renders it would be looking at different ones —
 * which is the bug this editor had, in the shape of a fallback.
 */
@Composable
private fun TaskEditorScaffold(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    titleState: TextFieldState,
    descriptionState: TextFieldState,
    isCompleted: Boolean,
    now: Instant,
    sheets: DialogState<TaskEditorSheet>,
    showAttachmentsRow: Boolean = true,
) {
    TaskEditorContent(
        taskId = model.taskId?.value ?: "",
        titleState = titleState,
        isCompleted = isCompleted,
        onCheckToggle = callbacks.onCheckToggle,
        descriptionState = descriptionState,
        priority = model.priority,
        onPriorityClear = callbacks.priority.onClear,
        dueDate = model.dueDate,
        dueTime = model.dueTime,
        onDueDateClear = callbacks.dueDate.onClear,
        showDueDate = true,
        onPriorityClick = callbacks.priority.onClick,
        onDueDateClick = callbacks.dueDate.onClick,
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
        blockedBy = model.blockedBy,
        availableTasks = model.availableTasks,
        checklistCount = model.checklist.size,
        attachmentsCount = model.attachments.size,
        checklistCallbacks = callbacks.checklist,
        attachmentsCallbacks = callbacks.attachments,
        showAttachmentsRow = showAttachmentsRow,
        extraSections = callbacks.extraSections,
        bottomBar = callbacks.bottomBar,
        menuItems = callbacks.menuItems,
        onBack = callbacks.onBack,
        onAiClick = callbacks.onAiClick,
        sheets = sheets,
    )

    TaskEditorSheetsHost(
        model = model,
        callbacks = callbacks,
        activeSheet = sheets.active,
        // Passed down so the time-entry sheet's "now" default comes from the one
        // place that resolved the clock, rather than from a clock this screen
        // reaches for itself (#91). `now` is required, so a caller that has not
        // decided what "now" is cannot compile.
        now = now,
        onSheetDismiss = { sheets.dismiss() },
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
        titleState = TextFieldState(""),
        isCompleted = false,
        onCheckToggle = null,
        descriptionState = TextFieldState(""),
        priority = TaskPriority.None,
        onPriorityClear = null,
        dueDate = null,
        dueTime = null,
        onDueDateClear = null,
        blockedBy = emptySet(),
        availableTasks = emptyList(),
        extraSections = null,
        bottomBar = null,
        menuItems = emptyList(),
        onBack = {},
        sheets = rememberDialogState(),
    )
}

@Preview
@Composable
private fun TaskEditorContentFilledPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val today = PreviewSamples.today
    TaskEditorContent(
        taskId = "",
        titleState = TextFieldState("Buy groceries"),
        isCompleted = false,
        onCheckToggle = null,
        descriptionState = TextFieldState("Milk, eggs, bread"),
        priority = TaskPriority.High,
        onPriorityClear = {},
        dueDate = today,
        dueTime = LocalTime(14, 30),
        onDueDateClear = {},
        blockedBy = emptySet(),
        availableTasks = emptyList(),
        extraSections = null,
        bottomBar = null,
        menuItems = listOf(
            TaskEditorMenuItem("Archive", onClick = {}),
            TaskEditorMenuItem("Delete", onClick = {}),

        ),
        onBack = {},
        sheets = rememberDialogState(),
    )
}

@Preview
@Composable
private fun TaskEditorContentDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    val today = PreviewSamples.today
    val sampleTask = PreviewSamples.task(id = "t2", title = "Review PR")
    TaskEditorContent(
        taskId = "",
        titleState = TextFieldState("Review PR"),
        isCompleted = false,
        onCheckToggle = null,
        descriptionState = TextFieldState(""),
        priority = TaskPriority.Urgent,
        onPriorityClear = {},
        dueDate = today,
        dueTime = null,
        onDueDateClear = {},
        blockedBy = setOf(sampleTask.id),
        availableTasks = listOf(sampleTask),
        extraSections = null,
        bottomBar = null,
        menuItems = listOf(
            TaskEditorMenuItem("Archive", onClick = {}),
            TaskEditorMenuItem("Delete", onClick = {}),

        ),
        onBack = {},
        sheets = rememberDialogState(),
    )
}
