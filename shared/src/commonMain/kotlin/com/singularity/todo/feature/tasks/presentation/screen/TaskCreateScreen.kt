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
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
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
import com.singularity.todo.feature.tasks.presentation.state.DraftAttachment
import com.singularity.todo.feature.tasks.presentation.state.DraftChecklistItem
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import kotlinx.datetime.LocalDate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Clock
import kotlin.time.Instant
import org.koin.compose.koinInject
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
fun TaskCreateScreen(initialDueDate: LocalDate?, draftKey: String? = null) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(initialDueDate, draftKey) }
    // The injected clock, not the system one. This screen hands `now` down to the
    // editor and stamps draft attachments with it, so a test that pins a clock in
    // Koin controls both. `koinInject` rather than a parameter: this is a nav
    // entry, and a parameter would have to be threaded from the graph for the
    // sake of a value the graph already provides.
    val clock: Clock = koinInject()
    val now: Instant = clock.now()
    val navigator = LocalTasksNavigator.current

    val state by vm.stateFlow.collectAsStateWithLifecycle()
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

    // The system back press goes through the same guard as the header's button.
    // Without this, the button asked "discard?" and the gesture did not: the
    // gesture popped the screen and the typed title was lost. With the keyboard
    // open, the gesture closes the keyboard first — that is what a back press is
    // for while the keyboard is up.
    ImeAwareBackHandler { guardedBack() }

    DiscardChangesDialogHost(
        visible = showDiscard,
        onDiscard = {
            showDiscard = false
            navigator.back()
        },
        onKeepEditing = { showDiscard = false },
    )

    val checklistItems = state.draft.checklist.toChecklistItems()
    val attachmentItems = state.draft.attachments.toAttachments(now)
    // The *whole* set of dates, primary date first. The row has to be able to say
    // "12 окт, 17 окт, 3 ноя" — a list without its primary date would name two
    // of the three.
    val dueDates = (
        listOfNotNull((state.draft.dueDate as? DueDateOption.Custom)?.date) +
            state.draft.extraDueDates
    ).toImmutableList()

    TaskCreateEditorContent(
        model = TaskEditorModel(
            taskId = null,
            priority = state.draft.priority,
            dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
            dueDates = dueDates,
            // The create editor is where several dates make sense: the work does
            // not exist yet, so there is nothing to split. Everywhere else a task
            // has one date.
            multiDateEnabled = true,
            dueTime = state.draft.dueTime,
            startDate = (state.draft.startDate as? DueDateOption.Custom)?.date,
            startTime = state.draft.startTime,
            project = state.draft.projectId?.let { ProjectId.fromString(it) },
            tags = state.draft.tagIds.map { TagId.fromString(it) }.toImmutableList(),
            checklist = checklistItems.toImmutableList(),
            attachments = attachmentItems.toImmutableList(),
            recurrence = state.draft.recurrence,
            isPinned = state.draft.isPinned,
            estimateMinutes = null,
            blockedBy = persistentSetOf(),
            availableTasks = persistentListOf(),
        ),
        onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
        onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
        callbacks = TaskEditorCallbacks(
            onBack = guardedBack,
            // Create mode: no task exists yet, so there is nothing to complete.
            onCheckToggle = null,
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
                onChangeDates = { dates -> vm.onIntent(TaskCreateIntent.SetDueDates(dates)) },
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
                // null, not an empty lambda: the task has no id until it is saved, so
                // there is nothing to attach the file to. The sheet hides its
                // "Add file" button rather than offering one that cannot work.
                onAttachFile = null,
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
            menuItems = persistentListOf(),
        ),
        titleSource = state.draft.titleSource,
        descriptionSource = state.draft.descriptionSource,
        now = now,
        // The state the rows open their sheets through. The editor renders the sheets
        // from the same state, so the row that opens one and the host that shows it are
        // looking at one thing.
        sheets = sheets,
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────────

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
            id = com.singularity.todo.core.attachments.AttachmentId(draft.url),
            taskId = com.singularity.todo.feature.tasks.domain.model.TaskId(""),
            userId = com.singularity.todo.core.ids.UserId.anonymous,
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            url = draft.url,
            title = draft.title ?: "",
            createdAt = now,
            updatedAt = now,
        )
    }
