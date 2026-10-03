package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.detail.AttachmentsCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.ChecklistCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.components.detail.ToggleCallbacks
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Task create screen for the tasks nested navigation graph.
 * Reads [LocalTasksNavigator] for all navigation — no callbacks needed.
 *
 * Uses [org.koin.compose.viewmodel.koinViewModel] with [parametersOf] for
 * per-entry ViewModel scoping (requires [rememberViewModelStoreNavEntryDecorator]
 * in the NavDisplay entry decorators).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateScreen(initialDueDate: LocalDate?, sectionPrefillKey: String? = null) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(initialDueDate, sectionPrefillKey) }
    val navigator = LocalTasksNavigator.current

    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val sheets = rememberDialogState<TaskEditorSheet>()

    CollectEvents(vm.events) { event ->
        if (event is com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiEvent.Saved) {
            isNavigatingBack = true
            navigator.back()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val guardedBack: () -> Unit = {
        when {
            isNavigatingBack -> Unit
            state.isSaving -> Unit
            state.isDirty -> showDiscard = true
            else -> navigator.back()
        }
    }

    if (showDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscard = false
                navigator.back()
            },
            onKeepEditing = { showDiscard = false },
        )
    }

    // Map draft checklist items to the ChecklistItem domain model for the editor model.
    val checklistItems = state.draft.checklist.map { draft ->
        com.singularity.todo.feature.checklist.ChecklistItem(
            id = ChecklistItemId.fromString(draft.id),
            taskId = "",
            title = draft.text,
            isCompleted = draft.isChecked,
        )
    }

    // Map draft attachments to the domain Attachment model for the editor model.
    val attachmentItems = state.draft.attachments.map { draft ->
        com.singularity.todo.core.attachments.Attachment(
            id = com.singularity.todo.core.attachments.AttachmentId(draft.url),
            taskId = com.singularity.todo.feature.tasks.domain.model.TaskId(""),
            userId = com.singularity.todo.core.ids.UserId.anonymous,
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            url = draft.url,
            title = draft.title ?: "",
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now(),
        )
    }

    TaskEditorContent(
        model = TaskEditorModel(
            taskId = null,
            titleDraft = state.draft.title,
            descriptionDraft = state.draft.description,
            priority = state.draft.priority,
            dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
            dueTime = state.draft.dueTime,
            startDate = (state.draft.startDate as? DueDateOption.Custom)?.date,
            startTime = state.draft.startTime,
            project = state.draft.projectId?.let { ProjectId.fromString(it) },
            tags = state.draft.tagIds.map { TagId.fromString(it) },
            checklist = checklistItems,
            attachments = attachmentItems,
            recurrence = state.draft.recurrence,
            isPinned = state.draft.isPinned,
            estimateMinutes = null,
            dependsOn = emptySet(),
            availableTasks = emptyList(),
        ),
        callbacks = TaskEditorCallbacks(
            onBack = guardedBack,
            onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
            onCheckToggle = {},
            onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
            priority = RowCallbacks(
                onChange = { vm.onIntent(TaskCreateIntent.SetPriority(it)) },
                onClick = { sheets.show(TaskEditorSheet.Priority) },
                onClear = { vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.None)) },
            ),
            dueDate = DateRowCallbacks(
                onChangeDate = { vm.onIntent(TaskCreateIntent.SetDueDate(it)) },
                onChangeTime = { vm.onIntent(TaskCreateIntent.SetDueTime(it)) },
                onClick = { sheets.show(TaskEditorSheet.Date) },
                onClear = { vm.onIntent(TaskCreateIntent.DueDateCleared) },
            ),
            startDate = DateRowCallbacks(
                onChangeDate = { vm.onIntent(TaskCreateIntent.SetStartDate(it)) },
                onChangeTime = { vm.onIntent(TaskCreateIntent.SetStartTime(it)) },
                onClick = { sheets.show(TaskEditorSheet.StartDate) },
                onClear = { vm.onIntent(TaskCreateIntent.SetStartDate(null)) },
            ),
            project = RowCallbacks(
                onChange = { vm.onIntent(TaskCreateIntent.SetProject(it)) },
                onClick = { sheets.show(TaskEditorSheet.Project) },
                onClear = { vm.onIntent(TaskCreateIntent.SetProject(null)) },
            ),
            tags = RowCallbacks(
                onChange = { ids -> vm.onIntent(TaskCreateIntent.SetTags(ids)) },
                onClick = { sheets.show(TaskEditorSheet.Tags) },
                onClear = { vm.onIntent(TaskCreateIntent.SetTags(emptyList())) },
            ),
            recurrence = RowCallbacks(
                onChange = { vm.onIntent(TaskCreateIntent.SetRecurrence(it)) },
                onClick = { sheets.show(TaskEditorSheet.Recurrence) },
                onClear = { vm.onIntent(TaskCreateIntent.SetRecurrence(null)) },
            ),
            pin = ToggleCallbacks(
                onToggle = { vm.onIntent(TaskCreateIntent.PinToggled) },
            ),
            estimate = null,
            onTimeEntryAdd = null,
            onTimeEntrySave = null,
            checklist = ChecklistCallbacks(
                onOpen = { sheets.show(TaskEditorSheet.Checklist) },
                onAdd = { text -> vm.onIntent(TaskCreateIntent.AddChecklistItem(text)) },
                onToggle = { id -> vm.onIntent(TaskCreateIntent.ToggleChecklistItem(id.value)) },
                onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveChecklistItem(id.value)) },
            ),
            attachments = AttachmentsCallbacks(
                onOpen = { sheets.show(TaskEditorSheet.Attachments) },
                onAddUrl = { url, title -> vm.onIntent(TaskCreateIntent.AddAttachmentUrl(url, title)) },
                onAttachFile = { /* file attachment not yet supported in create mode */ },
                onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveAttachmentUrl(id.value)) },
            ),
            dependencies = null,
            bottomBar = {
                TaskSaveBar(
                    isEnabled = state.isSaveEnabled,
                    isLoading = state.isSaving,
                    onSaveClick = { vm.onIntent(TaskCreateIntent.SaveClicked) },
                )
            },
            menuItems = emptyList(),
        ),
        isCompleted = false,
    )
}
