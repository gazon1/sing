package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.material3.SnackbarHost
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
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.DraftAttachment
import com.singularity.todo.feature.tasks.domain.state.DraftChecklistItem
import com.singularity.todo.feature.tasks.domain.state.DueDateOption
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.domain.state.TaskTitleValidation
import com.singularity.todo.feature.tasks.presentation.components.detail.AttachmentsCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.ChecklistCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskCreateEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.components.detail.ToggleCallbacks
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateScreen(
    initialDueDate: LocalDate?,
    draftKey: String? = null,
    initialProjectId: ProjectId? = null,
    initialTagIds: Set<TagId> = emptySet(),
) {
    // Positional, and matched by position in both places: the four parameters
    // here are destructured, in this order, by the `viewModel {
    // (initialDueDate, draftKey, initialProjectId, initialTagIds) }` factory in
    // `TasksDiModule`. Koin resolves them by order and by type, so reordering
    // or dropping one here does not fail the build — it fails at runtime, or
    // worse, binds a `Set<TagId>` to a `String?`-shaped slot. Change the two
    // together. `initialProjectId`/`initialTagIds` are the seed the agenda FAB
    // passes so a task created from a project's or tag's list belongs to it.
    val vm: TaskCreateViewModel =
        koinViewModel { parametersOf(initialDueDate, draftKey, initialProjectId, initialTagIds) }
    val clock: Clock = koinInject()
    val clock: Clock = koinInject()
    val now: Instant = clock.now()
    val navigator = LocalTasksNavigator.current

    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val sheets = rememberDialogState<TaskEditorSheet>()

    CollectEvents(vm.events) { event ->
        if (event is TaskCreateUiEvent.Saved) {
            isNavigatingBack = true
            navigator.back()
        }
    }

    val titleError = TaskTitleValidation.error(state.draft.title.text)

    // Non-validation errors (persistence failures, recurrence-dropped notice)
    // surface in the snackbar. The title validation message is drawn inline and
    // must not be duplicated. Key on `state.error` only.
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        if (message != TaskTitleValidation.REQUIRED_MESSAGE) {
            snackbarHostState.showSnackbar(message)
        }
    }

    val guardedBack: () -> Unit = {
        when {
            isNavigatingBack -> Unit
            state.isSaving -> Unit
            state.isDirty -> showDiscard = true
            else -> navigator.back()
        }
    }

    ImeAwareBackHandler { guardedBack() }

    DiscardChangesDialogHost(
        visible = showDiscard,
        onDiscard = {
            showDiscard = false
            vm.onIntent(TaskCreateIntent.Discard)
            navigator.back()
        },
        onKeepEditing = { showDiscard = false },
    )

    val checklistItems = state.draft.checklist.toChecklistItems()
    val attachmentItems = state.draft.attachments.toAttachments(now)
    val dueDates =
        remember(state.draft.dueDate, state.draft.extraDueDates) {
            val primary = listOfNotNull((state.draft.dueDate as? DueDateOption.Custom)?.date)
            (primary + state.draft.extraDueDates).toImmutableList()
        }

    TaskCreateEditorContent(
        model =
            TaskEditorModel(
                taskId = null,
                priority = state.draft.priority,
                dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
                dueDates = dueDates,
                multiDateEnabled = true,
                dueTime = state.draft.dueTime,
                startDate = (state.draft.startDate as? DueDateOption.Custom)?.date,
                startTime = state.draft.startTime,
                project = state.draft.projectId?.let { ProjectId.fromString(it) },
                tags =
                    remember(state.draft.tagIds) {
                        state.draft.tagIds
                            .map { TagId.fromString(it) }
                            .toImmutableList()
                    },
                checklist = remember(checklistItems) { checklistItems.toImmutableList() },
                attachments = remember(attachmentItems) { attachmentItems.toImmutableList() },
                recurrence = state.draft.recurrence,
                isPinned = state.draft.isPinned,
                estimateMinutes = null,
                blockedBy = persistentSetOf(),
                availableTasks = persistentListOf(),
            ),
        onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
        onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
        callbacks =
            TaskEditorCallbacks(
                onBack = guardedBack,
                onCheckToggle = null,
                priority =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetPriority(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Priority) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.None)) },
                    ),
                dueDate =
                    DateRowCallbacks(
                        onChangeDate = { vm.onIntent(TaskCreateIntent.SetDueDate(it)) },
                        onChangeTime = { vm.onIntent(TaskCreateIntent.SetDueTime(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Date) },
                        onClear = { vm.onIntent(TaskCreateIntent.DueDateCleared) },
                        onChangeDates = { dates -> vm.onIntent(TaskCreateIntent.SetDueDates(dates)) },
                    ),
                startDate =
                    DateRowCallbacks(
                        onChangeDate = { vm.onIntent(TaskCreateIntent.SetStartDate(it)) },
                        onChangeTime = { vm.onIntent(TaskCreateIntent.SetStartTime(it)) },
                        onClick = { sheets.show(TaskEditorSheet.StartDate) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetStartDate(null)) },
                    ),
                project =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetProject(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Project) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetProject(null)) },
                    ),
                tags =
                    RowCallbacks(
                        onChange = { ids -> vm.onIntent(TaskCreateIntent.SetTags(ids)) },
                        onClick = { sheets.show(TaskEditorSheet.Tags) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetTags(emptyList())) },
                    ),
                recurrence =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetRecurrence(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Recurrence) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetRecurrence(null)) },
                    ),
                pin =
                    ToggleCallbacks(
                        onToggle = { vm.onIntent(TaskCreateIntent.PinToggled) },
                    ),
                estimate = null,
                onTimeEntryAdd = null,
                onTimeEntrySave = null,
                checklist =
                    ChecklistCallbacks(
                        onOpen = { sheets.show(TaskEditorSheet.Checklist) },
                        onAdd = { text -> vm.onIntent(TaskCreateIntent.AddChecklistItem(text)) },
                        onToggle = { id -> vm.onIntent(TaskCreateIntent.ToggleChecklistItem(id.value)) },
                        onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveChecklistItem(id.value)) },
                    ),
                attachments =
                    AttachmentsCallbacks(
                        onOpen = { sheets.show(TaskEditorSheet.Attachments) },
                        onAddUrl = { url, title -> vm.onIntent(TaskCreateIntent.AddAttachmentUrl(url, title)) },
                        onAttachFile = null,
                        onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveAttachmentUrl(id.value)) },
                    ),
                dependencies = null,
                bottomBar = {
                    TaskSaveBar(
                        isEnabled = !state.isSaving && state.isSaveEnabled,
                        isLoading = state.isSaving,
                        onSaveClick = { vm.onIntent(TaskCreateIntent.SaveClicked) },
                    )
                },
                menuItems = persistentListOf(),
            ),
        titleSource = state.draft.titleSource,
        descriptionSource = state.draft.descriptionSource,
        now = now,
        titleError = titleError,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        sheets = sheets,
    )
}

@Composable
private fun DiscardChangesDialogHost(
    visible: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    if (visible) {
        DiscardChangesDialog(
            onDiscard = onDiscard,
            onKeepEditing = onKeepEditing,
        )
    }
}

private fun List<DraftChecklistItem>.toChecklistItems(): List<com.singularity.todo.feature.checklist.ChecklistItem> =
    map { draft ->
        com.singularity.todo.feature.checklist.ChecklistItem(
            id = ChecklistItemId.fromString(draft.id),
            taskId = "",
            title = draft.text,
            isCompleted = draft.isChecked,
        )
    }

private fun List<DraftAttachment>.toAttachments(now: Instant): List<com.singularity.todo.core.attachments.Attachment> =
    map { draft ->
        com.singularity.todo.core.attachments.Attachment(
            id =
                com.singularity.todo.core.attachments
                    .AttachmentId(draft.url),
            taskId =
                com.singularity.todo.feature.tasks.domain.model
                    .TaskId(""),
            userId = com.singularity.todo.core.ids.UserId.anonymous,
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            url = draft.url,
            title = draft.title ?: "",
            createdAt = now,
            updatedAt = now,
        )
    }
